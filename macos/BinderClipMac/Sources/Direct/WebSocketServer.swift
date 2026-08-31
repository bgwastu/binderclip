import AppKit
import CryptoKit
import Darwin
import Foundation
import Network
import SystemConfiguration

public enum PeerTransportType: String, Sendable {
    case lan = "LAN"
    case mesh = "Mesh"
    case bluetooth = "Bluetooth"
    case none = "None"
}

public final class WebSocketServer: @unchecked Sendable {
    public var onClipboard: ((String) -> Void)?
    public var onOpenURL: ((URL) -> Void)?
    public var onImage: ((ImagePayload) -> Void)?
    public var onTransferStatus: ((String) -> Void)?
    public var onPeersChanged: (([Peer]) -> Void)?
    public var onLog: ((String) -> Void)?
    public var onLocalNetworkPermissionRequired: ((Bool) -> Void)?

    public var isBluetoothPermissionDenied: Bool {
        bluetoothConnector.isBluetoothPermissionDenied
    }

    public func peerTransportType(_ peerID: String) -> PeerTransportType {
        let isBt = bluetoothConnector.authenticatedPeerIDSnapshot.contains(peerID)
        let isWs = queue.sync {
            activeSessions.values.first { $0.isAuthenticated && $0.peerID == peerID }?.remoteHostString()
        }
        if let wsHost = isWs {
            if wsHost.hasPrefix("100.") {
                return .mesh
            }
            return .lan
        }
        if isBt {
            return .bluetooth
        }
        return .none
    }

    private let queue = DispatchQueue(label: "net.wastu.binderclip.websocket.server", qos: .userInitiated)
    private let rosterManager = RosterManager()
    private var listener: NWListener?
    private let bluetoothConnector = BluetoothConnector()
    private var pathMonitor: NWPathMonitor?
    private var pathDebounce: DispatchWorkItem?
    private var lastLocalAddresses: [String] = []
    private var lastPathSatisfied = false
    private var dynamicStore: SCDynamicStore?
    private var dynamicStoreSource: CFRunLoopSource?
    private var addressSampler: DispatchSourceTimer?
    private var addressDebounce: DispatchWorkItem?
    private var heartbeatTimer: DispatchSourceTimer?
    private var wantsListener = false
    private var listenBackoffSeconds: TimeInterval = 1
    private var listenRestart: DispatchWorkItem?

    private var activeSessions: [ObjectIdentifier: WebSocketSession] = [:]
    private var lastProcessedHash: String = ""

    private let stateLock = NSLock()
    private var cachedDeviceName: String
    private var cachedPeersSnapshot: [Peer] = []

    public var localDeviceID: String { rosterManager.localID }
    public var localDeviceName: String {
        stateLock.lock(); defer { stateLock.unlock() }
        return cachedDeviceName
    }

    public var localEndpoint: DirectEndpoint {
        DirectEndpoint(host: Self.localAddresses().first ?? "unknown", port: SyncProtocol.defaultPort)
    }

    public init() {
        self.cachedDeviceName = rosterManager.localName
        self.cachedPeersSnapshot = rosterManager.peerSnapshot()
    }

    public func peersSnapshot() -> [Peer] {
        stateLock.lock(); defer { stateLock.unlock() }
        return cachedPeersSnapshot
    }

    public func setLocalDeviceName(_ name: String) {
        queue.async { [weak self] in
            guard let self else { return }
            let updated = self.rosterManager.setLocalName(name)
            self.updateCachedState()
            self.refreshBonjour()
            self.broadcastText(["type": "rename", "id": self.rosterManager.localID, "name": updated])
            self.publishPeers()
        }
    }

    public func renamePeer(id: String, newName: String) {
        if id == localDeviceID {
            setLocalDeviceName(newName)
            return
        }
        queue.async { [weak self] in
            guard let self else { return }
            guard self.rosterManager.renamePeer(id: id, newName: newName) else { return }
            let name = self.rosterManager.peers[id]?.name ?? newName
            self.broadcastText(["type": "rename", "id": id, "name": name])
            self.updateCachedState()
            self.publishPeers()
        }
    }

    public func start() {
        queue.async { [weak self] in
            guard let self else { return }
            self.rosterManager.markAllDisconnected()
            self.updateCachedState()
            self.publishPeers()
            self.wantsListener = true
            self.listenBackoffSeconds = 1
            self.startListener()
            self.startPathMonitor()
            self.startAddressWatch()
            self.startHeartbeat()
            self.bluetoothConnector.start(server: self)
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.wantsListener = false
            self.listenRestart?.cancel()
            self.listenRestart = nil
            self.pathDebounce?.cancel()
            self.pathDebounce = nil
            self.pathMonitor?.cancel()
            self.pathMonitor = nil
            self.stopAddressWatch()
            self.heartbeatTimer?.cancel()
            self.heartbeatTimer = nil
            self.listener?.cancel()
            self.listener = nil
            self.bluetoothConnector.stop()
            for session in self.activeSessions.values {
                session.connection.cancel()
            }
            self.activeSessions.removeAll()
            self.rosterManager.markAllDisconnected()
            self.updateCachedState()
            self.publishPeers()
        }
    }

    public func resetPairingKey() {
        queue.async { [weak self] in
            guard let self else { return }
            let sessions = Array(self.activeSessions.values)
            self.notifyUnpairAndDrop(sessions)
            _ = self.rosterManager.rotateGroupKey()
            self.updateCachedState()
            self.publishPeers()
            self.onLog?("New pairing key generated")
        }
    }

    public func unpairAll() {
        queue.async { [weak self] in
            guard let self else { return }
            let sessions = Array(self.activeSessions.values)
            self.rosterManager.forgetAllPeers()
            self.notifyUnpairAndDrop(sessions)
            self.updateCachedState()
            self.publishPeers()
            self.onLog?("Unpaired from all devices")
        }
    }

    public func removePeer(_ peerID: String) {
        queue.async { [weak self] in
            guard let self else { return }
            self.rosterManager.forgetPeer(id: peerID)
            let sessions = self.activeSessions.values.filter { $0.peerID == peerID }
            self.notifyUnpairAndDrop(Array(sessions))
            self.updateCachedState()
            self.publishPeers()
            self.onLog?("Removed peer")
        }
    }

    private func notifyUnpairAndDrop(_ sessions: [WebSocketSession]) {
        for session in bluetoothConnector.authenticatedSessions {
            session.sendFrame([("type", .text("unpair"))])
            session.close()
        }
        for session in sessions {
            let id = ObjectIdentifier(session.connection)
            session.sendText(["type": "unpair"]) { [weak self] in
                self?.queue.async {
                    guard let self else { return }
                    session.connection.cancel()
                    self.activeSessions.removeValue(forKey: id)
                }
            }
            queue.asyncAfter(deadline: .now() + 0.75) { [weak self] in
                guard let self, self.activeSessions[id] === session else { return }
                session.connection.cancel()
                self.activeSessions.removeValue(forKey: id)
            }
        }
    }

    public func createInvite() -> URL? {
        queue.sync {
            let endpoints = SyncProtocol.advertisedEndpoints(from: Self.localAddresses())
            guard !endpoints.isEmpty else { return nil }
            let psk = SyncProtocol.urlSafeBase64(rosterManager.groupKey)
            return SyncProtocol.createPairingURL(
                deviceId: localDeviceID,
                deviceName: localDeviceName,
                psk: psk,
                endpoints: endpoints
            )
        }
    }

    public func sendClipboard(_ text: String, targetDeviceId: String? = nil) {
        let hash = SyncProtocol.sha256Hex(text)
        queue.async { [weak self] in
            guard let self else { return }
            guard hash != self.lastProcessedHash else { return }
            self.lastProcessedHash = hash

            var payload: [String: Any] = [
                "type": "clipboard",
                "eventId": UUID().uuidString,
                "originId": self.localDeviceID,
                "text": text,
                "hash": hash,
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ]
            if let targetDeviceId {
                payload["targetDeviceId"] = targetDeviceId
            }
            let authedCount = self.activeSessions.values.filter { $0.isAuthenticated }.count
            let btAuthedCount = self.bluetoothConnector.authenticatedSessions.count
            print("[WebSocketServer] Broadcasting clipboard to \(authedCount) ws and \(btAuthedCount) bt sessions: \(text.prefix(30))")
            self.onLog?("Broadcasting clipboard to \(authedCount) ws and \(btAuthedCount) bt sessions")
            self.broadcastText(payload, targetPeerId: targetDeviceId)
        }
    }

    public func sendOpenURL(_ url: URL, targetDeviceId: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            var payload: [String: Any] = [
                "type": "openUrl",
                "eventId": UUID().uuidString,
                "originId": self.localDeviceID,
                "url": url.absoluteString
            ]
            if let targetDeviceId {
                payload["targetDeviceId"] = targetDeviceId
            }
            self.broadcastText(payload, targetPeerId: targetDeviceId)
        }
    }

    public func sendImage(_ image: ImagePayload, targetDeviceId: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            guard image.sha256 != self.lastProcessedHash else { return }
            self.lastProcessedHash = image.sha256

            let packet = SyncProtocol.packImage(image: image, originId: self.localDeviceID)
            guard !packet.isEmpty else { return }
            self.broadcastBinary(packet, targetPeerId: targetDeviceId)
            self.onTransferStatus?("Sent image (\(image.mimeType))")
        }
    }

    private func startListener() {
        guard wantsListener, listener == nil else { return }
        do {
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.enableKeepalive = true
            tcpOptions.keepaliveIdle = 15

            let wsOptions = NWProtocolWebSocket.Options()
            wsOptions.autoReplyPing = true
            wsOptions.maximumMessageSize = SyncProtocol.maximumImageBytes
            wsOptions.setClientRequestHandler(queue) { [weak self] _, _ in
                self?.onLog?("Accepted WebSocket handshake")
                return NWProtocolWebSocket.Response(status: .accept, subprotocol: nil)
            }

            let params = NWParameters(tls: nil, tcp: tcpOptions)
            params.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)
            params.allowLocalEndpointReuse = true

            let port = NWEndpoint.Port(rawValue: SyncProtocol.defaultPort) ?? .any
            let listener = try NWListener(using: params, on: port)

            listener.service = Self.makeBonjourService(
                deviceID: localDeviceID,
                deviceName: localDeviceName
            )

            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.listenBackoffSeconds = 1
                    self.onLog?("Listening on port \(SyncProtocol.defaultPort)")
                    self.onLocalNetworkPermissionRequired?(false)
                case .failed(let error):
                    self.onLog?("Listener failed: \(error.localizedDescription)")
                    if case .posix(let code) = error, code == POSIXErrorCode.EPERM {
                        self.onLocalNetworkPermissionRequired?(true)
                    }
                    guard self.listener != nil else { return }
                    let failed = self.listener
                    self.listener = nil
                    failed?.cancel()
                    self.scheduleListenerRestart()
                default:
                    break
                }
            }

            listener.newConnectionHandler = { [weak self] connection in
                self?.acceptIncoming(connection)
            }

            listener.start(queue: queue)
            self.listener = listener
        } catch {
            onLog?("Could not start listener: \(error.localizedDescription)")
            scheduleListenerRestart()
        }
    }

    private func scheduleListenerRestart() {
        guard wantsListener else { return }
        listenRestart?.cancel()
        let delay = listenBackoffSeconds
        listenBackoffSeconds = min(listenBackoffSeconds * 2, SyncProtocol.listenerBackoffCap)
        let work = DispatchWorkItem { [weak self] in
            self?.startListener()
        }
        listenRestart = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
        onLog?("Retrying listener in \(Int(delay))s")
    }

    private func acceptIncoming(_ nwConnection: NWConnection) {
        let session = WebSocketSession(connection: nwConnection)
        let id = ObjectIdentifier(nwConnection)
        activeSessions[id] = session
        print("[WebSocketServer] Accepting incoming connection: \(nwConnection.endpoint)")
        fflush(stdout)

        nwConnection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            print("[WebSocketServer] Connection \(nwConnection.endpoint) state: \(state)")
            fflush(stdout)
            switch state {
            case .ready:
                self.bindSession(session)
                self.scheduleAuthDeadline(session)
                self.readNextMessage(from: session)
                self.publishPresence()
            case .waiting:
                self.publishPresence()
            case .failed(let error):
                print("[WebSocketServer] Incoming connection failed: \(error)")
                self.handleConnectionClosed(session: session)
            case .cancelled:
                self.handleConnectionClosed(session: session)
            default:
                break
            }
        }

        nwConnection.start(queue: queue)
    }

    private func handleConnectionClosed(session: WebSocketSession) {
        let id = ObjectIdentifier(session.connection)
        guard activeSessions.removeValue(forKey: id) != nil else { return }
        session.authDeadline?.cancel()
        session.authDeadline = nil
        session.connection.cancel()
        publishPresence()
    }

    private func scheduleAuthDeadline(_ session: WebSocketSession) {
        session.authDeadline?.cancel()
        let work = DispatchWorkItem { [weak self, weak session] in
            guard let self, let session else { return }
            let id = ObjectIdentifier(session.connection)
            guard self.activeSessions[id] === session, !session.isAuthenticated else { return }
            self.onLog?("Auth deadline exceeded")
            session.connection.cancel()
        }
        session.authDeadline = work
        queue.asyncAfter(deadline: .now() + SyncProtocol.authDeadline, execute: work)
    }

    private func usableAuthenticatedPeerIDs() -> [String] {
        let now = Date()
        let locals = Self.localAddresses()
        return activeSessions.values.compactMap { session in
            guard session.isAuthenticated, let peerID = session.peerID else { return nil }
            guard SessionLiveness.isAlive(
                boundLocal: session.boundLocalAddress,
                currentLocals: locals,
                lastHeard: session.lastHeard,
                now: now,
                budget: session.livenessBudget
            ) else { return nil }
            if case .ready = session.connection.state { return peerID }
            return nil
        }
    }

    private func publishPresence() {
        for peerID in rosterManager.peers.keys {
            let connected = PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: usableAuthenticatedPeerIDs())
            rosterManager.setPeerConnected(peerID, connected: connected)
        }
        updateCachedState()
        publishPeers()
    }

    private func readNextMessage(from session: WebSocketSession) {
        session.connection.receiveMessage { [weak self, weak session] data, context, isComplete, error in
            guard let self, let session else { return }
            if error != nil {
                self.handleConnectionClosed(session: session)
                return
            }

            if let data, !data.isEmpty {
                let isBinary = (context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata)?.opcode == .binary
                if isBinary {
                    self.handleBinaryMessage(data, from: session)
                } else {
                    self.handleTextMessage(data, from: session)
                }
            }

            self.readNextMessage(from: session)
        }
    }

    private func handleTextMessage(_ data: Data, from session: WebSocketSession) {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }
        session.lastHeard = Date()

        switch type {
        case "auth", "hello":
            handleAuthMessage(json, session: session)

        case "clipboard":
            guard session.isAuthenticated else { return }
            if let text = json["text"] as? String {
                let hash = json["hash"] as? String ?? SyncProtocol.sha256Hex(text)
                guard hash != lastProcessedHash else { return }
                lastProcessedHash = hash
                DispatchQueue.main.async { [weak self] in
                    self?.onClipboard?(text)
                }
                broadcastText(json, excludeSessionId: ObjectIdentifier(session.connection))
            }

        case "openUrl":
            guard session.isAuthenticated else { return }
            if let urlStr = json["url"] as? String, let url = URL(string: urlStr) {
                DispatchQueue.main.async { [weak self] in
                    self?.onOpenURL?(url)
                }
            }

        case "rename":
            guard session.isAuthenticated else { return }
            if let peerID = json["id"] as? String, let name = json["name"] as? String {
                let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return }
                if peerID == localDeviceID {
                    _ = rosterManager.setLocalName(trimmed)
                } else {
                    _ = rosterManager.renamePeer(id: peerID, newName: trimmed)
                }
                updateCachedState()
                publishPeers()
                broadcastText(
                    ["type": "rename", "id": peerID, "name": trimmed],
                    excludeSessionId: ObjectIdentifier(session.connection)
                )
            }

        case "ping":
            session.sendText(["type": "pong", "t": json["t"] as Any])

        case "pong":
            break

        case "power":
            guard session.isAuthenticated else { return }
            applyPowerState(json["state"] as? String, session: session)

        default:
            break
        }
    }

    private func handleAuthMessage(_ json: [String: Any], session: WebSocketSession) {
        let token = json["token"] as? String ?? json["psk"] as? String
        guard let token,
              let clientID = json["deviceId"] as? String,
              let clientName = json["deviceName"] as? String else {
            print("[WebSocketServer] Auth failed: missing token/deviceId/deviceName")
            session.connection.cancel()
            return
        }

        let match = Self.pskMatches(token, groupKey: rosterManager.groupKey)
        print("[WebSocketServer] Auth check: token=\(token.prefix(10))... match=\(match)")
        fflush(stdout)
        guard match else {
            onLog?("Unauthorized peer connection rejected")
            print("[WebSocketServer] PSK mismatch! Rejecting \(clientName)")
            session.connection.cancel()
            return
        }

        let isPairingScan = json["pairing"] as? Bool ?? false
        guard rosterManager.shouldAcceptPeer(clientID, isPairingScan: isPairingScan) else {
            onLog?("Unpaired device reconnect ignored")
            session.sendText(["type": "unpair"]) {
                session.connection.cancel()
            }
            return
        }

        let incomingID = ObjectIdentifier(session.connection)
        guard PeerPresence.shouldProcessAuth(
            isStillActive: activeSessions[incomingID] === session,
            alreadyAuthenticated: session.isAuthenticated
        ) else { return }

        session.authDeadline?.cancel()
        session.authDeadline = nil
        session.isAuthenticated = true
        session.peerID = clientID
        session.peerName = clientName
        session.livenessBudget = SyncProtocol.heartbeatBudget

        for (id, other) in activeSessions where id != incomingID && PeerPresence.shouldCancelExtra(
            isAuthenticated: other.isAuthenticated,
            existingPeerID: other.peerID,
            incomingPeerID: clientID
        ) {
            other.authDeadline?.cancel()
            other.authDeadline = nil
            other.connection.cancel()
            activeSessions.removeValue(forKey: id)
        }

        let platform = json["platform"] as? String ?? "Android"
        let remoteHost = session.remoteHostString() ?? "unknown"
        let endpoint = DirectEndpoint(host: remoteHost, port: SyncProtocol.defaultPort)
        let peer = Peer(id: clientID, name: clientName, endpoint: endpoint, connected: true, platform: platform)

        _ = rosterManager.addOrUpdatePeer(peer)
        bindSession(session)
        publishPresence()

        session.sendText([
            "type": "auth_ok",
            "deviceId": localDeviceID,
            "deviceName": localDeviceName,
            "version": SyncProtocol.version,
            "endpoints": advertisedEndpointList()
        ])
        onLog?("Connected to \(clientName)")
    }

    private func handleBinaryMessage(_ data: Data, from session: WebSocketSession) {
        guard session.isAuthenticated else { return }
        session.lastHeard = Date()
        guard let (meta, imgData) = SyncProtocol.unpackImage(data),
              let image = try? ImagePayload(id: UUID(uuidString: meta.id) ?? UUID(), mimeType: meta.mimeType, data: imgData) else {
            return
        }

        guard image.sha256 != lastProcessedHash else { return }
        lastProcessedHash = image.sha256

        DispatchQueue.main.async { [weak self] in
            self?.onImage?(image)
            self?.onTransferStatus?("Received image (\(image.mimeType))")
        }

        broadcastBinary(data, excludeSessionId: ObjectIdentifier(session.connection))
    }

    private func broadcastText(_ object: [String: Any], targetPeerId: String? = nil, excludeSessionId: ObjectIdentifier? = nil) {
        let locals = Self.localAddresses()
        for (id, session) in activeSessions where session.isAuthenticated {
            if let excludeSessionId, id == excludeSessionId { continue }
            if let targetPeerId, session.peerID != targetPeerId { continue }
            if SessionLiveness.shouldEvict(boundLocal: session.boundLocalAddress, currentLocals: locals) { continue }
            session.sendText(object)
        }
        for session in bluetoothConnector.authenticatedSessions {
            if let targetPeerId, session.peerID != targetPeerId { continue }
            if let fields = Self.btFields(from: object) {
                session.sendFrame(fields)
            }
        }
    }

    static func pskMatches(_ token: String, groupKey: Data) -> Bool {
        token == SyncProtocol.urlSafeBase64(groupKey)
            || token == groupKey.base64EncodedString()
            || SyncProtocol.decodeBase64(token) == groupKey
    }

    /// Canonical CBOR field order for frames mirrored onto the Bluetooth link.
    static func btFields(from object: [String: Any]) -> [(String, BtValue)]? {
        guard let type = object["type"] as? String else { return nil }
        switch type {
        case "clipboard":
            return [("type", .text(type)), ("text", .text(object["text"] as? String ?? ""))]
        case "openUrl":
            guard let url = object["url"] as? String else { return nil }
            return [("type", .text(type)), ("url", .text(url))]
        case "ping", "pong":
            return [("type", .text(type)), ("t", .uint(UInt64(object["t"] as? Int64 ?? 0)))]
        case "unpair":
            return [("type", .text(type))]
        case "rename":
            return [("type", .text(type)), ("id", .text(object["id"] as? String ?? "")), ("name", .text(object["name"] as? String ?? ""))]
        default:
            return nil
        }
    }

    private func broadcastBinary(_ data: Data, targetPeerId: String? = nil, excludeSessionId: ObjectIdentifier? = nil) {
        let locals = Self.localAddresses()
        for (id, session) in activeSessions where session.isAuthenticated {
            if let excludeSessionId, id == excludeSessionId { continue }
            if let targetPeerId, session.peerID != targetPeerId { continue }
            if SessionLiveness.shouldEvict(boundLocal: session.boundLocalAddress, currentLocals: locals) { continue }
            session.sendBinary(data)
        }
    }

    private func startPathMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.pathDebounce?.cancel()
            let work = DispatchWorkItem { [weak self] in
                self?.handlePathUpdate(path)
            }
            self.pathDebounce = work
            self.queue.asyncAfter(deadline: .now() + 0.2, execute: work)
        }
        monitor.start(queue: queue)
        self.pathMonitor = monitor
    }

    private func handlePathUpdate(_ path: NWPath) {
        let satisfied = path.status == .satisfied
        let regained = satisfied && !lastPathSatisfied
        lastPathSatisfied = satisfied
        if !satisfied {
            refreshAddresses(reason: "path-unsatisfied")
            onLog?("Default path unsatisfied")
            return
        }
        if regained {
            refreshAddresses(reason: "path-regained")
        }
    }

    private func startAddressWatch() {
        lastLocalAddresses = Self.localAddresses()
        var context = SCDynamicStoreContext(
            version: 0,
            info: Unmanaged.passUnretained(self).toOpaque(),
            retain: nil,
            release: nil,
            copyDescription: nil
        )
        if let store = SCDynamicStoreCreate(
            nil,
            "net.wastu.binderclip.addresses" as CFString,
            { _, _, info in
                guard let info else { return }
                Unmanaged<WebSocketServer>.fromOpaque(info).takeUnretainedValue().scheduleAddressRefresh()
            },
            &context
        ) {
            let patterns = ["State:/Network/Interface/.*/IPv4", "State:/Network/Global/IPv4"] as CFArray
            SCDynamicStoreSetNotificationKeys(store, nil, patterns)
            if let source = SCDynamicStoreCreateRunLoopSource(nil, store, 0) {
                CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
                dynamicStoreSource = source
            }
            dynamicStore = store
        }
        let sampler = DispatchSource.makeTimerSource(queue: queue)
        sampler.schedule(deadline: .now() + 2, repeating: 2)
        sampler.setEventHandler { [weak self] in
            self?.refreshAddresses(reason: "sample")
        }
        sampler.resume()
        addressSampler = sampler
    }

    private func stopAddressWatch() {
        addressDebounce?.cancel()
        addressDebounce = nil
        addressSampler?.cancel()
        addressSampler = nil
        let source = dynamicStoreSource
        dynamicStoreSource = nil
        dynamicStore = nil
        if let source {
            DispatchQueue.main.async {
                CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes)
            }
        }
    }

    private func scheduleAddressRefresh() {
        addressDebounce?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.refreshAddresses(reason: "interfaces")
        }
        addressDebounce = work
        queue.asyncAfter(deadline: .now() + 0.2, execute: work)
    }

    private func refreshAddresses(reason: String) {
        let addresses = Self.localAddresses()
        guard addresses != lastLocalAddresses else { return }
        lastLocalAddresses = addresses
        refreshBonjour()
        broadcastCurrentEndpoints()
        evictSessionsMissingBind(currentLocals: addresses)
        publishPresence()
        onLog?("Addresses changed (\(reason)): \(addresses.joined(separator: ", "))")
    }

    private func evictSessionsMissingBind(currentLocals: [String]) {
        for session in activeSessions.values where SessionLiveness.shouldEvict(boundLocal: session.boundLocalAddress, currentLocals: currentLocals) {
            onLog?("Evicting session bound to \(session.boundLocalAddress ?? "unknown")")
            session.connection.cancel()
        }
    }

    private func bindSession(_ session: WebSocketSession) {
        let remote = session.remoteHostString() ?? ""
        session.boundLocalAddress = SessionLiveness.boundLocalAddress(remote: remote, localAddresses: Self.localAddresses())
        session.lastHeard = Date()
    }

    private func startHeartbeat() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + SyncProtocol.heartbeatInterval, repeating: SyncProtocol.heartbeatInterval)
        timer.setEventHandler { [weak self] in
            self?.tickHeartbeat()
        }
        timer.resume()
        heartbeatTimer = timer
    }

    private func tickHeartbeat() {
        let now = Date()
        let locals = Self.localAddresses()
        var evicted = false
        for session in activeSessions.values {
            guard session.isAuthenticated else { continue }
            if !SessionLiveness.isAlive(
                boundLocal: session.boundLocalAddress,
                currentLocals: locals,
                lastHeard: session.lastHeard,
                now: now,
                budget: session.livenessBudget
            ) {
                session.connection.cancel()
                evicted = true
                continue
            }
            session.sendText([
                "type": "ping",
                "t": Int64(now.timeIntervalSince1970 * 1000)
            ])
        }
        for session in bluetoothConnector.authenticatedSessions where session.isAuthenticated {
            if !SessionLiveness.isAlive(
                boundLocal: nil,
                currentLocals: [],
                lastHeard: session.lastHeard,
                now: now,
                budget: session.livenessBudget
            ) {
                session.close()
                evicted = true
                continue
            }
            session.sendFrame([
                ("type", .text("ping")),
                ("t", .uint(UInt64(Int64(now.timeIntervalSince1970 * 1000))))
            ])
        }
        if evicted {
            publishPresence()
        }
    }

    // MARK: - Bluetooth frames

    var hasNoLiveSessions: Bool {
        queue.sync {
            activeSessions.values.allSatisfy { !$0.isAuthenticated }
        } && bluetoothConnector.authenticatedSessions.isEmpty
    }

    var hasPairedPeers: Bool { !rosterManager.peers.isEmpty }

    /// Peer ids currently holding an authenticated session (WebSocket or Bluetooth).
    /// Runs on the server queue only; the Bluetooth snapshot is lock-protected so it is safe to
    /// read even from the connector's own serial queue without deadlocking.
    var livePeerIDs: Set<String> {
        var ids = Set(activeSessions.values.compactMap { $0.isAuthenticated ? $0.peerID : nil })
        ids.formUnion(bluetoothConnector.authenticatedPeerIDSnapshot)
        return ids
    }

    /// Runs on the server queue; the connector hops here from its own serial queue.
    func handleBluetoothFrame(_ fields: [(String, BtValue)], session: BluetoothSession) {
        queue.async { [weak self] in
            self?.handleBluetoothFrameLocked(fields, session: session)
        }
    }

    func bluetoothSessionClosed(_ session: BluetoothSession) {
        queue.async { [weak self] in
            guard let self, let peerID = session.peerID else { return }
            if var peer = self.rosterManager.peers[peerID] {
                peer.connected = false
                _ = self.rosterManager.addOrUpdatePeer(peer)
            }
            self.publishPresence()
            self.onLog?("Bluetooth session closed (\(peerID))")
        }
    }

    private func handleBluetoothFrameLocked(_ fields: [(String, BtValue)], session: BluetoothSession) {
        func text(_ key: String) -> String? {
            fields.first(where: { $0.0 == key })?.1.valueAsText
        }
        func uint(_ key: String) -> UInt64? {
            if case .uint(let number)? = fields.first(where: { $0.0 == key })?.1 { return number }
            return nil
        }
        let type = text("type") ?? ""
        switch type {
        case "auth":
            let token = text("psk") ?? ""
            let clientID = text("deviceId") ?? ""
            let clientName = text("deviceName") ?? "Android"
            guard Self.pskMatches(token, groupKey: rosterManager.groupKey),
                  rosterManager.shouldAcceptPeer(clientID, isPairingScan: false) else {
                onLog?("Unauthorized Bluetooth connection rejected")
                bluetoothConnector.dropSession(session)
                return
            }
            session.isAuthenticated = true
            session.peerID = clientID
            session.peerName = clientName
            session.livenessBudget = SyncProtocol.heartbeatBudget
            session.cancelAuthDeadline()
            self.bluetoothConnector.registerBluetoothMapping(btName: session.deviceName, peerID: clientID)

            for other in activeSessions.values where PeerPresence.shouldCancelExtra(
                isAuthenticated: other.isAuthenticated,
                existingPeerID: other.peerID,
                incomingPeerID: clientID
            ) {
                other.connection.cancel()
            }

            let peer = Peer(id: clientID, name: clientName, endpoint: DirectEndpoint(host: "bluetooth", port: 0), connected: true, platform: "Android")
            _ = rosterManager.addOrUpdatePeer(peer)
            publishPresence()

            var okFields: [(String, BtValue)] = [
                ("type", .text("auth_ok")),
                ("deviceId", .text(localDeviceID)),
                ("deviceName", .text(localDeviceName)),
                ("version", .uint(UInt64(SyncProtocol.version))),
                ("endpoints", .array(advertisedEndpointList())),
            ]
            _ = okFields.count
            session.sendFrame(okFields)
            onLog?("Connected to \(clientName) over Bluetooth")
        case "clipboard":
            guard session.isAuthenticated, let body = text("text") else { return }
            session.lastHeard = Date()
            let digest = SyncProtocol.sha256Hex(body)
            print("[WebSocketServer] BT received clipboard: \(body.prefix(30)), hash=\(digest)")
            guard digest != lastProcessedHash else { return }
            lastProcessedHash = digest
            DispatchQueue.main.async { [weak self] in
                self?.onClipboard?(body)
            }
        case "openUrl":
            guard session.isAuthenticated, let url = text("url"), let value = URL(string: url) else { return }
            session.lastHeard = Date()
            print("[WebSocketServer] BT received openUrl: \(url)")
            DispatchQueue.main.async { [weak self] in
                self?.onOpenURL?(value)
            }
        case "power":
            guard session.isAuthenticated else { return }
            session.livenessBudget = text("state") == "sleep" ? SyncProtocol.heartbeatSleepBudget : SyncProtocol.heartbeatBudget
        default:
            break
        }
    }

    private func applyPowerState(_ state: String?, session: WebSocketSession) {
        switch state {
        case "sleep":
            session.livenessBudget = SyncProtocol.heartbeatSleepBudget
        case "awake":
            session.livenessBudget = SyncProtocol.heartbeatBudget
        default:
            break
        }
    }

    private func advertisedEndpointList() -> [String] {
        SyncProtocol.advertisedEndpoints(from: Self.localAddresses())
    }

    private func broadcastCurrentEndpoints() {
        let endpoints = advertisedEndpointList()
        guard !endpoints.isEmpty else { return }
        broadcastText(["type": "endpoints", "endpoints": endpoints])
    }

    private func refreshBonjour() {
        listener?.service = Self.makeBonjourService(
            deviceID: localDeviceID,
            deviceName: localDeviceName
        )
    }

    /// TXT payload for the `_binderclip._tcp` advertisement. Android matches `id` against its
    /// paired peer so discovery on a shared LAN ignores foreign BinderClip Macs.
    static func bonjourTXTRecords(deviceID: String, deviceName: String, version: Int) -> [String: String] {
        ["id": deviceID, "name": deviceName, "v": String(version)]
    }

    static func makeBonjourService(deviceID: String, deviceName: String) -> NWListener.Service {
        NWListener.Service(
            name: deviceName,
            type: "_binderclip._tcp",
            txtRecord: NWTXTRecord(bonjourTXTRecords(
                deviceID: deviceID,
                deviceName: deviceName,
                version: SyncProtocol.version
            ))
        )
    }

    private func updateCachedState() {
        stateLock.lock()
        cachedDeviceName = rosterManager.localName
        cachedPeersSnapshot = rosterManager.peerSnapshot()
        stateLock.unlock()
    }

    private func publishPeers() {
        let snapshot = peersSnapshot()
        DispatchQueue.main.async { [weak self] in
            self?.onPeersChanged?(snapshot)
        }
    }

    public static func localAddresses() -> [String] {
        var addresses: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }

        var ptr = first
        while true {
            let flags = Int32(ptr.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) != 0
            let isLoopback = (flags & IFF_LOOPBACK) != 0
            let isRunning = (flags & IFF_RUNNING) != 0

            if isUp && isRunning && !isLoopback, let addr = ptr.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: hostname)
                    if !ip.hasPrefix("169.254.") && ip != "127.0.0.1" {
                        addresses.append(ip)
                    }
                }
            }
            guard let next = ptr.pointee.ifa_next else { break }
            ptr = next
        }
        return addresses
    }
}

private final class WebSocketSession: @unchecked Sendable {
    let connection: NWConnection
    var isAuthenticated: Bool = false
    var peerID: String?
    var peerName: String?
    var boundLocalAddress: String?
    var lastHeard: Date?
    var livenessBudget: TimeInterval = SyncProtocol.heartbeatBudget
    var authDeadline: DispatchWorkItem?

    init(connection: NWConnection) {
        self.connection = connection
    }

    func sendText(_ object: [String: Any], onComplete: (() -> Void)? = nil) {
        do {
            let data = try JSONSerialization.data(withJSONObject: object)
            let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
            let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { error in
                if let error {
                    print("[WebSocketSession] sendText error: \(error)")
                }
                onComplete?()
            })
        } catch {
            print("[WebSocketSession] JSONSerialization failed: \(error)")
            onComplete?()
        }
    }

    func sendBinary(_ data: Data) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binary", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { error in
            if let error {
                print("[WebSocketSession] sendBinary error: \(error)")
            }
        })
    }

    func remoteHostString() -> String? {
        if case .hostPort(let host, _) = connection.endpoint {
            switch host {
            case .ipv4(let ip): return "\(ip)"
            case .ipv6(let ip): return "\(ip)"
            case .name(let name, _): return name
            @unknown default: return nil
            }
        }
        return nil
    }
}
