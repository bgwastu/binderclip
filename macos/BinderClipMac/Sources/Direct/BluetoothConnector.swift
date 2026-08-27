import Foundation
import IOBluetooth

/// One connected phone over Bluetooth Classic RFCOMM. The Mac dials out; the phone speaks first
/// (auth frame), mirroring WebSocket direction-of-trust so PSK handling stays identical.
final class BluetoothSession {
    let channel: IOBluetoothRFCOMMChannel
    let deviceName: String
    private let writeQueue = DispatchQueue(label: "net.wastu.binderclip.bluetooth.write")
    var isAuthenticated = false
    var peerID: String?
    var peerName: String?
    var lastHeard = Date()
    var livenessBudget: TimeInterval = SyncProtocol.heartbeatBudget
    var reader = BtFrameIo.Reader()
    private var authDeadline: DispatchWorkItem?

    init(channel: IOBluetoothRFCOMMChannel, deviceName: String) {
        self.channel = channel
        self.deviceName = deviceName
    }

    func sendFrame(_ fields: [(String, BtValue)]) {
        guard let payload = try? BtCbor.encode(fields),
              let framed = try? BtFrameIo.frame(payload: payload) else { return }
        writeQueue.async { [weak self] in
            self?.writeNow(framed)
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

    /// RFCOMM writes are UInt16-capped; large frames are sliced sequentially.
    private func writeNow(_ bytes: [UInt8]) {
        var offset = 0
        while offset < bytes.count {
            let end = min(offset + 0xFFFF, bytes.count)
            let slice = Array(bytes[offset..<end])
            let result = slice.withUnsafeBufferPointer { pointer -> IOReturn in
                guard let base = pointer.baseAddress else { return kIOReturnError }
                return channel.writeSync(UnsafeMutableRawPointer(mutating: base), length: UInt16(pointer.count))
            }
            guard result == kIOReturnSuccess else {
                channel.close()
                return
            }
            offset = end
        }
    }
}

/// Dialer + session owner for paired phones over RFCOMM. Dials only while no live sessions exist,
/// so Wi-Fi/mesh always wins and idle radios stay quiet.
final class BluetoothConnector: NSObject, IOBluetoothRFCOMMChannelDelegate {
    private weak var server: WebSocketServer?
    private let queue = DispatchQueue(label: "net.wastu.binderclip.bluetooth")
    private var dialTimer: DispatchSourceTimer?
    private var dialInFlight = false
    private var pendingAuthByChannel: [ObjectIdentifier: BluetoothSession] = [:]
    private var authenticatedByChannel: [ObjectIdentifier: BluetoothSession] = [:]
    /// Bytes received before the session registered (the phone sends its auth frame the moment it
    /// accepts, which can beat rfcommChannelOpenComplete); replayed once the session exists.
    private var earlyBytesByChannel: [ObjectIdentifier: [UInt8]] = [:]
    private var bluetoothNameToPeerID: [String: String] = [:]
    /// Next RFCOMM channel to probe for each Bluetooth device name (SDP drifts on Android, so we
    /// walk the window from the SDP-resolved channel upward until a connection lands).
    private var nextProbeByDevice: [String: UInt8] = [:]
    private let peerStateLock = NSLock()
    private var authenticatedPeerSnapshot: Set<String> = []
    var logHandler: ((String) -> Void)?
    /// Upper bound (inclusive) of the RFCOMM channel probe window.
    private let probeWindowTop: UInt8 = 40
    /// Devices that currently have a probe open; we probe only one channel per device at a time so
    /// RFCOMM multiplexing cannot flood the phone with simultaneous accepted sockets.
    private var dialingNow: Set<String> = []

    func start(server: WebSocketServer) {
        self.server = server
        logHandler = server.onLog
        if let saved = UserDefaults.standard.dictionary(forKey: "bt-name-map") as? [String: String] {
            bluetoothNameToPeerID = saved
        }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 10, repeating: 30.0)
        timer.setEventHandler { [weak self] in self?.dialIfIdle() }
        timer.resume()
        dialTimer = timer
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.dialTimer?.cancel()
            self.dialTimer = nil
            for session in self.authenticatedByChannel.values { session.channel.close() }
            for session in self.pendingAuthByChannel.values { session.channel.close() }
            self.authenticatedByChannel.removeAll()
            self.pendingAuthByChannel.removeAll()
        }
    }

    var authenticatedSessions: [BluetoothSession] {
        queue.sync { Array(authenticatedByChannel.values) }
    }

    /// Lock-protected snapshot of authenticated peer ids, safe to read from the connector queue.
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

    private func log(_ message: String) {
        DispatchQueue.main.async { [weak self] in self?.logHandler?(message) }
    }

    // MARK: - Dialing

    private func dialIfIdle() {
        guard let server else { return }
        guard !dialInFlight else { return }
        let allDevices = IOBluetoothDevice.pairedDevices() ?? []
        let devices: [IOBluetoothDevice] = allDevices.compactMap { $0 as? IOBluetoothDevice }
        guard !devices.isEmpty else { return }
        dialInFlight = true
        defer { dialInFlight = false }
        var raw = UUID(uuidString: SyncProtocol.btServiceUuid)!.uuid
        let targetUUID = IOBluetoothSDPUUID(data: Data(bytes: &raw, count: MemoryLayout.size(ofValue: raw)))
        let livePeers = server.livePeerIDs
        for device in devices {
            let btName = device.name ?? ""
            if let mappedID = bluetoothNameToPeerID[btName], livePeers.contains(mappedID) {
                continue
            }
            // Only BinderClip phones expose our service record (or are already learned in the map);
            // skip keyboards/headsets/etc. so we never dial unrelated peripherals.
            var baseChannel: UInt8 = 14
            if let record = device.getServiceRecord(for: targetUUID) {
                var sdpChannel: BluetoothRFCOMMChannelID = 0
                if record.getRFCOMMChannelID(&sdpChannel) == kIOReturnSuccess, sdpChannel > 0 {
                    baseChannel = sdpChannel
                }
            } else if bluetoothNameToPeerID[btName] == nil {
                continue
            }
            let channelID = nextProbeByDevice[btName] ?? baseChannel
            if channelID > probeWindowTop {
                // Window exhausted this cycle; reset so future cycles retry from the base.
                nextProbeByDevice[btName] = baseChannel
                continue
            }
            dialDevice(device, channelID: channelID)
        }
    }

    /// Learned after a successful BT auth so future dials skip this phone while it is connected.
    func registerBluetoothMapping(btName: String, peerID: String) {
        queue.async { [weak self] in
            guard let self else { return }
            self.bluetoothNameToPeerID[btName] = peerID
            UserDefaults.standard.set(self.bluetoothNameToPeerID, forKey: "bt-name-map")
        }
    }

    private func expirePending(_ identifier: ObjectIdentifier) {
        guard let session = pendingAuthByChannel.removeValue(forKey: identifier) else { return }
        log("Bluetooth auth timeout")
        session.channel.close()
        dialingNow.remove(session.deviceName)
        // Advance the probe window promptly instead of waiting for the next dial cycle.
        let next = nextProbeByDevice[session.deviceName]
        guard let next, next <= probeWindowTop else { return }
        if let device = IOBluetoothDevice.pairedDevices()?.compactMap({ $0 as? IOBluetoothDevice })
            .first(where: { $0.name == session.deviceName }) {
            dialDevice(device, channelID: next)
        }
    }

    private func dialDevice(_ device: IOBluetoothDevice, channelID: UInt8) {
        let name = device.name ?? ""
        guard channelID <= probeWindowTop, !dialingNow.contains(name), !hasSession(for: name) else { return }
        dialingNow.insert(name)
        nextProbeByDevice[name] = channelID + 1
        var channel: IOBluetoothRFCOMMChannel?
        let status = device.openRFCOMMChannelAsync(&channel, withChannelID: channelID, delegate: self)
        if status != kIOReturnSuccess {
            // Dialing failed to queue; release the probe lock so the next cycle continues.
            dialingNow.remove(name)
            nextProbeByDevice[name] = min(channelID + 1, probeWindowTop)
            return
        }
        // Safety: if the channel never opens (wrong channel), advance after a short probe budget.
        let nameForDeadline = name
        DispatchQueue.global().asyncAfter(deadline: .now() + SyncProtocol.authDeadline + 3) { [weak self] in
            self?.queue.async {
                guard let self, self.dialingNow.contains(nameForDeadline), !self.hasSession(for: nameForDeadline) else { return }
                self.dialingNow.remove(nameForDeadline)
                let next = self.nextProbeByDevice[nameForDeadline] ?? UInt8(channelID + 1)
                guard next <= self.probeWindowTop,
                      let dev = IOBluetoothDevice.pairedDevices()?.compactMap({ $0 as? IOBluetoothDevice })
                        .first(where: { $0.name == nameForDeadline }) else { return }
                self.dialDevice(dev, channelID: next)
            }
        }
    }

    private func hasSession(for deviceName: String) -> Bool {
        pendingAuthByChannel.values.contains { $0.deviceName == deviceName }
            || authenticatedByChannel.values.contains { $0.deviceName == deviceName }
    }

    // MARK: - IOBluetoothRFCOMMChannelDelegate

    func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel, data dataPointer: UnsafeMutableRawPointer, length dataLength: Int) {
        let bytes = Array(UnsafeBufferPointer(start: dataPointer.assumingMemoryBound(to: UInt8.self), count: dataLength))
        queue.async { [weak self] in
            self?.consume(bytes, from: rfcommChannel)
        }
    }

    func rfcommChannelOpenComplete(_ rfcommChannel: IOBluetoothRFCOMMChannel, status error: IOReturn) {
        guard error == kIOReturnSuccess else {
            queue.async { [weak self] in
                self?.expirePending(ObjectIdentifier(rfcommChannel))
            }
            return
        }
        // The channel reference is only valid once the open callback fires; register the session
        // here (dialDevice cannot, since the inout channel is nil until this delegate runs).
        queue.async { [weak self] in
            guard let self else { return }
            let identifier = ObjectIdentifier(rfcommChannel)
            guard self.pendingAuthByChannel[identifier] == nil,
                  self.authenticatedByChannel[identifier] == nil else { return }
            let session = BluetoothSession(channel: rfcommChannel, deviceName: self.deviceName(for: rfcommChannel))
            self.pendingAuthByChannel[identifier] = session
            self.dialingNow.remove(session.deviceName)
            session.scheduleAuthDeadline { [weak self] in
                self?.queue.async { self?.expirePending(identifier) }
            }
            self.log("Dialing \(session.deviceName) over Bluetooth")
        }
    }

    private func deviceName(for channel: IOBluetoothRFCOMMChannel) -> String {
        channel.getDevice()?.name ?? ""
    }

    func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel) {
        queue.async { [weak self] in
            guard let self else { return }
            let identifier = ObjectIdentifier(rfcommChannel)
            self.pendingAuthByChannel.removeValue(forKey: identifier)
            self.earlyBytesByChannel.removeValue(forKey: identifier)
            guard let session = self.authenticatedByChannel.removeValue(forKey: identifier) else { return }
            self.dialingNow.remove(session.deviceName)
            if let pid = session.peerID { self.notePeerDropped(pid) }
            self.server?.bluetoothSessionClosed(session)
        }
    }

    func dropSession(_ session: BluetoothSession) {
        queue.async { [weak self] in
            let identifier = ObjectIdentifier(session.channel)
            self?.pendingAuthByChannel.removeValue(forKey: identifier)
            self?.authenticatedByChannel.removeValue(forKey: identifier)
            self?.earlyBytesByChannel.removeValue(forKey: identifier)
            self?.dialingNow.remove(session.deviceName)
            session.channel.close()
        }
    }

    private func consume(_ bytes: [UInt8], from channel: IOBluetoothRFCOMMChannel) {
        let identifier = ObjectIdentifier(channel)
        guard let session = pendingAuthByChannel[identifier] ?? authenticatedByChannel[identifier] else {
            earlyBytesByChannel[identifier, default: []].append(contentsOf: bytes)
            return
        }
        session.lastHeard = Date()
        if let early = earlyBytesByChannel.removeValue(forKey: identifier) {
            process(bytes: early, session: session, channel: channel)
        }
        process(bytes: bytes, session: session, channel: channel)
    }

    private func process(bytes: [UInt8], session: BluetoothSession, channel: IOBluetoothRFCOMMChannel) {
        let payloads: [[UInt8]]
        do {
            payloads = try session.reader.feed(bytes)
        } catch {
            channel.close()
            return
        }
        for payload in payloads {
            guard let fields = try? BtCbor.decode(payload) else { continue }
            server?.handleBluetoothFrame(fields, session: session)
        }
        if session.isAuthenticated, pendingAuthByChannel.removeValue(forKey: ObjectIdentifier(channel)) != nil {
            authenticatedByChannel[ObjectIdentifier(channel)] = session
            if let pid = session.peerID { noteAuthenticatedPeer(pid) }
        }
    }
}
