import Foundation
import CoreBluetooth

/// Protocol for a connected Bluetooth session (L2CAP channel or GATT stream).
protocol BluetoothSession: AnyObject {
    var id: ObjectIdentifier { get }
    var deviceName: String { get }
    var isAuthenticated: Bool { get set }
    var clipboardSync: Bool { get set }
    var peerID: String? { get set }
    var peerName: String? { get set }
    var lastHeard: Date { get set }
    var livenessBudget: TimeInterval { get set }
    var reader: BtFrameIo.Reader { get set }
    var nextPing: Date { get set }
    var lastProgressReport: Date { get set }
    var handshake: SecureHandshake { get }
    var secure: SecureChannel? { get set }

    func sendFrame(_ fields: [(String, BtValue)])
    func scheduleAuthDeadline(_ onCancel: @escaping () -> Void)
    func cancelAuthDeadline()
    func close()
}

/// BLE L2CAP Connection-Oriented Channel session (high-throughput stream transport).
final class BleL2capSession: NSObject, BluetoothSession, StreamDelegate {
    var id: ObjectIdentifier { ObjectIdentifier(self) }
    let channel: CBL2CAPChannel
    let deviceName: String
    private let queue: DispatchQueue
    private var pendingWrites: [Data] = []
    private var writeOffset = 0
    private var queuedBytes = 0
    var isAuthenticated = false
    var clipboardSync = false
    var peerID: String?
    var peerName: String?
    var lastHeard = SessionLiveness.now()
    var nextPing = SessionLiveness.now()
    var lastProgressReport = Date(timeIntervalSince1970: 0)
    var livenessBudget: TimeInterval = SyncProtocol.heartbeatBudget
    var reader = BtFrameIo.Reader()
    let handshake = SecureHandshake()
    var secure: SecureChannel?
    private var authDeadline: DispatchWorkItem?
    private var isClosed = false
    private let onData: (Data, BleL2capSession) -> Void
    private let onClose: (BleL2capSession) -> Void

    init(
        channel: CBL2CAPChannel,
        deviceName: String,
        queue: DispatchQueue,
        onData: @escaping (Data, BleL2capSession) -> Void,
        onClose: @escaping (BleL2capSession) -> Void
    ) {
        self.channel = channel
        self.deviceName = deviceName
        self.queue = queue
        self.onData = onData
        self.onClose = onClose
        super.init()

        channel.inputStream.delegate = self
        channel.outputStream.delegate = self
        channel.inputStream.schedule(in: .main, forMode: .common)
        channel.outputStream.schedule(in: .main, forMode: .common)
        channel.inputStream.open()
        channel.outputStream.open()
    }

    func sendFrame(_ fields: [(String, BtValue)]) {
        let outgoing: [(String, BtValue)]
        if fields.first(where: { $0.0 == "type" })?.1.valueAsText == "secure_hello", secure == nil {
            outgoing = fields
        } else {
            guard let secure, let plaintext = try? BtCbor.encode(fields),
                  let packet = try? secure.seal(Data(plaintext), kind: 3) else { close(); return }
            outgoing = [("type", .text("secure")), ("data", .text(packet.base64EncodedString()))]
        }
        guard let payload = try? BtCbor.encode(outgoing),
              let framed = try? BtFrameIo.frame(payload: payload) else { close(); return }
        queue.async { [weak self] in
            guard let self, !self.isClosed else { return }
            guard self.queuedBytes + framed.count <= 3_000_000 else { self.close(); return }
            self.pendingWrites.append(Data(framed))
            self.queuedBytes += framed.count
            self.flushWrites()
        }
    }

    private func flushWrites() {
        guard !isClosed else { return }
        while let data = pendingWrites.first, channel.outputStream.hasSpaceAvailable {
            let written = data.withUnsafeBytes { buffer -> Int in
                guard let base = buffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return -1 }
                return channel.outputStream.write(base.advanced(by: writeOffset), maxLength: data.count - writeOffset)
            }
            if written < 0 { close(); return }
            if written == 0 { return }
            writeOffset += written
            queuedBytes -= written
            if writeOffset == data.count { pendingWrites.removeFirst(); writeOffset = 0 }
        }
    }

    func scheduleAuthDeadline(_ onCancel: @escaping () -> Void) {
        let work = DispatchWorkItem { onCancel() }
        authDeadline = work
        DispatchQueue.global().asyncAfter(deadline: .now() + SyncProtocol.authDeadline, execute: work)
    }

    func cancelAuthDeadline() {
        authDeadline?.cancel()
        authDeadline = nil
    }

    func close() {
        queue.async { [weak self] in
            guard let self, !self.isClosed else { return }
            self.isClosed = true
            self.cancelAuthDeadline()
            self.pendingWrites.removeAll(); self.queuedBytes = 0
            self.channel.inputStream.close()
            self.channel.outputStream.close()
            self.onClose(self)
        }
    }

    func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case .hasBytesAvailable:
            if aStream == channel.inputStream {
                var buffer = [UInt8](repeating: 0, count: 4096)
                let readCount = channel.inputStream.read(&buffer, maxLength: buffer.count)
                if readCount > 0 {
                    let data = Data(buffer[0..<readCount])
                    queue.async { [weak self] in
                        guard let self, !self.isClosed else { return }
                        self.onData(data, self)
                    }
                } else if readCount < 0 {
                    close()
                }
            }
        case .hasSpaceAvailable:
            queue.async { [weak self] in self?.flushWrites() }
        case .errorOccurred, .endEncountered:
            close()
        default:
            break
        }
    }
}

/// Fallback GATT session for devices lacking L2CAP CoC support.
final class BleGattSession: BluetoothSession {
    var id: ObjectIdentifier { ObjectIdentifier(central) }
    let central: CBCentral
    let deviceName: String
    private weak var manager: CBPeripheralManager?
    private let notifyCharacteristic: CBMutableCharacteristic
    private let queue: DispatchQueue
    var isAuthenticated = false
    var clipboardSync = false
    var peerID: String?
    var peerName: String?
    var lastHeard = SessionLiveness.now()
    var nextPing = SessionLiveness.now()
    var lastProgressReport = Date(timeIntervalSince1970: 0)
    var livenessBudget: TimeInterval = SyncProtocol.heartbeatBudget
    var reader = BtFrameIo.Reader()
    let handshake = SecureHandshake()
    var secure: SecureChannel?
    private var authDeadline: DispatchWorkItem?
    private var isClosed = false
    private var pendingOutgoing: [Data] = []
    private var pendingIndex = 0
    private var pendingBytes = 0
    private let onClose: (BleGattSession) -> Void

    init(
        central: CBCentral,
        manager: CBPeripheralManager,
        notifyCharacteristic: CBMutableCharacteristic,
        queue: DispatchQueue,
        onClose: @escaping (BleGattSession) -> Void
    ) {
        self.central = central
        self.deviceName = central.identifier.uuidString
        self.manager = manager
        self.notifyCharacteristic = notifyCharacteristic
        self.queue = queue
        self.onClose = onClose
    }

    func sendFrame(_ fields: [(String, BtValue)]) {
        let outgoing: [(String, BtValue)]
        if fields.first(where: { $0.0 == "type" })?.1.valueAsText == "secure_hello", secure == nil {
            outgoing = fields
        } else {
            guard let secure, let plaintext = try? BtCbor.encode(fields),
                  let packet = try? secure.seal(Data(plaintext), kind: 3) else { close(); return }
            outgoing = [("type", .text("secure")), ("data", .text(packet.base64EncodedString()))]
        }
        guard let payload = try? BtCbor.encode(outgoing),
              let framed = try? BtFrameIo.frame(payload: payload) else { close(); return }
        queue.async { [weak self] in
            guard let self, !self.isClosed else { return }
            self.enqueueAndSend(Data(framed))
        }
    }

    private func enqueueAndSend(_ data: Data) {
        guard pendingBytes + data.count <= 3_000_000 else { close(); return }
        if pendingIndex > 1024 && pendingIndex > pendingOutgoing.count / 2 {
            pendingOutgoing.removeFirst(pendingIndex); pendingIndex = 0
        }
        pendingBytes += data.count
        let chunkSize = max(20, min(central.maximumUpdateValueLength, 512))
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            let chunk = data.subdata(in: offset..<end)
            pendingOutgoing.append(chunk)
            offset = end
        }
        flushPending()
    }

    func flushPending() {
        defer { if pendingIndex == pendingOutgoing.count { pendingOutgoing.removeAll(keepingCapacity: true); pendingIndex = 0 } }
        guard let manager, !isClosed else { return }
        while pendingIndex < pendingOutgoing.count {
            let next = pendingOutgoing[pendingIndex]
            let success = manager.updateValue(next, for: notifyCharacteristic, onSubscribedCentrals: [central])
            if success {
                pendingIndex += 1
                pendingBytes -= next.count
            } else {
                break
            }
        }
    }

    func scheduleAuthDeadline(_ onCancel: @escaping () -> Void) {
        let work = DispatchWorkItem { onCancel() }
        authDeadline = work
        DispatchQueue.global().asyncAfter(deadline: .now() + SyncProtocol.authDeadline, execute: work)
    }

    func cancelAuthDeadline() {
        authDeadline?.cancel()
        authDeadline = nil
    }

    func close() {
        queue.async { [weak self] in
            guard let self, !self.isClosed else { return }
            self.isClosed = true
            self.pendingOutgoing.removeAll(); self.pendingBytes = 0; self.pendingIndex = 0
            self.cancelAuthDeadline()
            self.onClose(self)
        }
    }
}

/// CoreBluetooth BLE Peripheral listener for paired BinderClip phones.
/// Advertises the BinderClip Service UUID and publishes an L2CAP Channel with GATT fallback.
final class BluetoothConnector: NSObject, CBPeripheralManagerDelegate {
    private weak var server: WebSocketServer?
    private let queue = DispatchQueue(label: "net.wastu.binderclip.bluetooth")
    private var manager: CBPeripheralManager?
    private var psmCharacteristic: CBMutableCharacteristic?
    private var writeCharacteristic: CBMutableCharacteristic?
    private var notifyCharacteristic: CBMutableCharacteristic?
    private var publishedPSM: CBL2CAPPSM = 0
    private var activeSessions: [ObjectIdentifier: BluetoothSession] = [:]
    private var pendingSessions: [ObjectIdentifier: BluetoothSession] = [:]
    private let peerStateLock = NSLock()
    private var authenticatedPeerSnapshot: Set<String> = []
    var logHandler: ((String) -> Void)?
    private var radioPowerState: CBManagerState = .unknown
    private var lastPeripheralReset: Date?
    private var running = false
    private var needed = false
    private var recoveryAttempts = 0

    /// Exact radio state reported by the peripheral manager (unlike permission checks).
    var isBluetoothPoweredOn: Bool {
        queue.sync { radioPowerState == .poweredOn }
    }

    /// Re-arm the peripheral after the user re-enables Bluetooth in System Settings.
    /// CoreBluetooth only reports the radio state transition on the *next* manager;
    /// the current manager never recovers on its own once it settles on `.poweredOff`.
    func bluetoothStateChanged() {
        queue.async { [weak self] in
            guard let self, self.running, self.needed, self.radioPowerState != .poweredOn else { return }
            self.recoveryAttempts = 0
            self.resetPeripheralIfNeeded(force: true)
        }
    }

    /// Re-create the peripheral manager while the radio reads as off, throttled. When the radio
    /// comes back on, the fresh manager observes `.poweredOn` and re-advertises — a stuck manager
    /// would otherwise never learn the radio state changed.
    func retryPeripheralIfOff() {
        queue.async { [weak self] in
            guard let self else { return }
            guard self.running, self.needed, self.radioPowerState == .poweredOff else { return }
            let now = SessionLiveness.now()
            if let last = self.lastPeripheralReset, now.timeIntervalSince(last) < min(300, 30 * pow(2, Double(min(self.recoveryAttempts, 4)))) { return }
            self.resetPeripheralIfNeeded(force: true)
            self.lastPeripheralReset = now
            self.recoveryAttempts += 1
        }
    }

    private func resetPeripheralIfNeeded(force: Bool) {
        guard force else { return }
        EnergyCounters.add("ble.manager.created")
        manager?.stopAdvertising()
        if publishedPSM != 0 { manager?.unpublishL2CAPChannel(publishedPSM); publishedPSM = 0 }
        manager?.removeAllServices()
        manager?.delegate = nil
        manager = nil
        psmCharacteristic = nil
        writeCharacteristic = nil
        notifyCharacteristic = nil
        log("Creating Bluetooth peripheral manager")
        manager = CBPeripheralManager(delegate: self, queue: queue, options: [
            CBPeripheralManagerOptionShowPowerAlertKey: true
        ])
        log("Bluetooth peripheral initial state=\(manager?.state.rawValue ?? -1), authorization=\(CBPeripheralManager.authorization.rawValue)")
    }

    func start(server: WebSocketServer) {
        queue.async { [weak self] in
            guard let self else { return }
            self.server = server
            self.logHandler = server.onLog
            self.running = true
            if self.needed { self.resetPeripheralIfNeeded(force: true) }
        }
    }

    func setNeeded(_ needed: Bool) {
        queue.async { [weak self] in
            guard let self, self.needed != needed else { return }
            self.needed = needed
            guard self.running else { return }
            self.log("Bluetooth discovery \(needed ? "enabled" : "paused")")
            if needed {
                self.recoveryAttempts = 0
                if self.manager == nil { self.resetPeripheralIfNeeded(force: true) }
                else if self.manager?.state == .poweredOn { self.setupServiceAndAdvertise() }
            } else { self.manager?.stopAdvertising() }
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.running = false
            self.manager?.stopAdvertising()
            if self.publishedPSM != 0 {
                self.manager?.unpublishL2CAPChannel(self.publishedPSM)
                self.publishedPSM = 0
            }
            self.manager?.removeAllServices()
            self.manager?.delegate = nil
            self.manager = nil
            self.psmCharacteristic = nil
            self.writeCharacteristic = nil
            self.notifyCharacteristic = nil
            for session in self.activeSessions.values { session.close() }
            for session in self.pendingSessions.values { session.close() }
            self.activeSessions.removeAll()
            self.pendingSessions.removeAll()
            self.peerStateLock.lock()
            self.authenticatedPeerSnapshot.removeAll()
            self.peerStateLock.unlock()
        }
    }

    func isCurrentSession(_ session: BluetoothSession) -> Bool {
        queue.sync { running && (activeSessions[session.id] === session || pendingSessions[session.id] === session) }
    }

    var authenticatedSessions: [BluetoothSession] {
        queue.sync { Array(activeSessions.values.filter { $0.isAuthenticated }) }
    }

    var isBluetoothPermissionDenied: Bool {
        if #available(macOS 10.15, *) {
            return CBPeripheralManager.authorization == .denied || CBPeripheralManager.authorization == .restricted
        }
        return false
    }

    var authenticatedPeerIDSnapshot: Set<String> {
        peerStateLock.lock()
        defer { peerStateLock.unlock() }
        return authenticatedPeerSnapshot
    }

    func noteAuthenticatedPeer(_ peerID: String) {
        peerStateLock.lock()
        authenticatedPeerSnapshot.insert(peerID)
        peerStateLock.unlock()
    }

    func notePeerDropped(_ peerID: String) {
        peerStateLock.lock()
        authenticatedPeerSnapshot.remove(peerID)
        peerStateLock.unlock()
    }

    func dropSession(_ session: BluetoothSession) {
        queue.async { [weak self] in
            guard let self else { return }
            if self.pendingSessions[session.id] === session { self.pendingSessions.removeValue(forKey: session.id) }
            if self.activeSessions[session.id] === session { self.activeSessions.removeValue(forKey: session.id) }
            session.close()
        }
    }

    func registerBluetoothMapping(btName: String, peerID: String) {
        // BLE uses direct peerID auth handshake without needing name maps
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard running, manager === peripheral else { return }
        radioPowerState = peripheral.state
        guard peripheral.state == .poweredOn else {
            log("Bluetooth radio not powered on (\(peripheral.state.rawValue)) — re-enable Bluetooth in System Settings to use the fallback")
            return
        }
        log("Bluetooth radio powered on — (re)advertising BinderClip peripheral")
        recoveryAttempts = 0
        if needed { setupServiceAndAdvertise() }
    }

    private func setupServiceAndAdvertise() {
        guard running, needed, let manager, manager.state == .poweredOn else { return }
        if writeCharacteristic != nil, notifyCharacteristic != nil {
            startAdvertising(manager)
            return
        }
        manager.removeAllServices()

        let serviceUUID = CBUUID(string: SyncProtocol.btServiceUuid)
        let psmCharUUID = CBUUID(string: SyncProtocol.btPsmCharUuid)
        let writeCharUUID = CBUUID(string: SyncProtocol.btWriteCharUuid)
        let notifyCharUUID = CBUUID(string: SyncProtocol.btNotifyCharUuid)

        let psmChar = CBMutableCharacteristic(
            type: psmCharUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let writeChar = CBMutableCharacteristic(
            type: writeCharUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let notifyChar = CBMutableCharacteristic(
            type: notifyCharUUID,
            properties: [.notify, .read],
            value: nil,
            permissions: [.readable]
        )

        self.psmCharacteristic = psmChar
        self.writeCharacteristic = writeChar
        self.notifyCharacteristic = notifyChar

        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [psmChar, writeChar, notifyChar]
        manager.add(service)

        // Publish dynamic L2CAP Channel (application PSK authenticates the session)
        #if DEBUG
        if ProcessInfo.processInfo.environment["BINDERCLIP_TEST_GATT_ONLY"] == "1" {
            log("GATT-only physical test mode; L2CAP publication disabled")
        } else {
            manager.publishL2CAPChannel(withEncryption: false)
        }
        #else
        manager.publishL2CAPChannel(withEncryption: false)
        #endif

        startAdvertising(manager)
    }

    private func startAdvertising(_ manager: CBPeripheralManager) {
        guard running, needed, self.manager === manager else { return }
        let localName = server?.localDeviceName ?? "BinderClip Mac"
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: SyncProtocol.btServiceUuid)],
            CBAdvertisementDataLocalNameKey: localName
        ])
        log("Bluetooth BLE peripheral advertising as '\(localName)'")
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        guard running, manager === peripheral else { return }
        if let error { log("Bluetooth advertising failed: \(error.localizedDescription)") }
        else { log("Bluetooth advertising started") }
        if !needed { peripheral.stopAdvertising() }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        guard running, manager === peripheral else { return }
        if let error {
            log("Failed to publish L2CAP channel: \(error.localizedDescription)")
            return
        }
        publishedPSM = PSM
        log("Published Bluetooth BLE L2CAP channel (PSM: \(PSM))")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didUnpublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        guard running, manager === peripheral, publishedPSM == PSM else { return }
        publishedPSM = 0
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard running, manager === peripheral else { return }
        guard let channel, error == nil else {
            log("L2CAP channel open failed: \(error?.localizedDescription ?? "unknown error")")
            return
        }
        let devName = channel.peer.identifier.uuidString
        log("Accepted Bluetooth L2CAP connection from \(devName)")
        let session = BleL2capSession(
            channel: channel,
            deviceName: devName,
            queue: queue,
            onData: { [weak self] data, sess in
                self?.handleIncomingBytes(Array(data), session: sess)
            },
            onClose: { [weak self] sess in
                self?.handleSessionClosed(sess)
            }
        )
        registerPendingSession(session)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard running, manager === peripheral else { return }
        if request.characteristic.uuid == CBUUID(string: SyncProtocol.btPsmCharUuid) {
            var psmLE = publishedPSM.littleEndian
            let data = Data(bytes: &psmLE, count: MemoryLayout<CBL2CAPPSM>.size)
            if request.offset > data.count {
                manager?.respond(to: request, withResult: .invalidOffset)
                return
            }
            request.value = data.subdata(in: request.offset..<data.count)
            manager?.respond(to: request, withResult: .success)
            return
        }
        manager?.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard running, manager === peripheral else { return }
        for request in requests {
            if request.characteristic.uuid == CBUUID(string: SyncProtocol.btWriteCharUuid), let val = request.value {
                let central = request.central
                let id = ObjectIdentifier(central)
                let session = activeSessions[id] ?? pendingSessions[id] ?? createGattSession(for: central)
                handleIncomingBytes(Array(val), session: session)
            }
            manager?.respond(to: request, withResult: .success)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard running, manager === peripheral else { return }
        if characteristic.uuid == CBUUID(string: SyncProtocol.btNotifyCharUuid) {
            _ = createGattSession(for: central)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard running, manager === peripheral else { return }
        let id = ObjectIdentifier(central)
        if let session = activeSessions.removeValue(forKey: id) ?? pendingSessions.removeValue(forKey: id) {
            session.close()
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        guard running, manager === peripheral else { return }
        for session in activeSessions.values {
            if let gatt = session as? BleGattSession { gatt.flushPending() }
        }
        for session in pendingSessions.values {
            if let gatt = session as? BleGattSession { gatt.flushPending() }
        }
    }

    private func createGattSession(for central: CBCentral) -> BleGattSession {
        let id = ObjectIdentifier(central)
        if let existing = pendingSessions[id] as? BleGattSession { return existing }
        if let existing = activeSessions[id] as? BleGattSession { return existing }
        guard let manager, let notify = notifyCharacteristic else { fatalError("notifyCharacteristic not initialized") }
        let session = BleGattSession(
            central: central,
            manager: manager,
            notifyCharacteristic: notify,
            queue: queue,
            onClose: { [weak self] sess in
                self?.handleSessionClosed(sess)
            }
        )
        registerPendingSession(session)
        return session
    }

    private func registerPendingSession(_ session: BluetoothSession) {
        pendingSessions[session.id] = session
        session.sendFrame([("type", .text("secure_hello")), ("version", .uint(UInt64(SyncProtocol.version))), ("nonce", .text(session.handshake.nonce.base64EncodedString()))])
        session.scheduleAuthDeadline { [weak self] in
            self?.queue.async {
                guard let self, self.pendingSessions[session.id] != nil else { return }
                self.log("Bluetooth auth timeout")
                self.dropSession(session)
            }
        }
    }

    /// Promote an authenticated session from pending → active. The auth frame is parsed on the
    /// server queue (async), so `handleIncomingBytes`'s synchronous `isAuthenticated` check runs
    /// too early and the session would otherwise sit in `pendingSessions` until the phone's next
    /// frame — leaving it out of broadcasts/heartbeats for up to a heartbeat interval.
    func promoteSession(_ session: BluetoothSession) {
        queue.async { [weak self] in
            guard let self, self.running, session.isAuthenticated, self.pendingSessions[session.id] === session else { return }
            self.pendingSessions.removeValue(forKey: session.id)
            self.activeSessions[session.id] = session
            if let pid = session.peerID { self.noteAuthenticatedPeer(pid) }
        }
    }

    private func handleIncomingBytes(_ bytes: [UInt8], session: BluetoothSession) {
        let now = SessionLiveness.now()
        if session.isAuthenticated, now.timeIntervalSince(session.lastProgressReport) >= 1 {
            session.lastProgressReport = now
            server?.noteBluetoothProgress(session)
        }
        let payloads: [[UInt8]]
        do {
            payloads = try session.reader.feed(bytes)
        } catch {
            dropSession(session)
            return
        }
        for payload in payloads {
            guard let fields = try? BtCbor.decode(payload) else { continue }
            server?.handleBluetoothFrame(fields, session: session)
        }
    }

    private func handleSessionClosed(_ session: BluetoothSession) {
        if pendingSessions[session.id] === session { pendingSessions.removeValue(forKey: session.id) }
        let removed = activeSessions[session.id] === session ? activeSessions.removeValue(forKey: session.id) : nil
        if let pid = session.peerID, !activeSessions.values.contains(where: { $0.isAuthenticated && $0.peerID == pid }) { notePeerDropped(pid) }
        if removed != nil || session.isAuthenticated {
            server?.bluetoothSessionClosed(session)
        }
    }

    private func log(_ message: String) {
        DispatchQueue.main.async { [weak self] in self?.logHandler?(message) }
    }
}
