import AppKit
import CryptoKit
import Darwin
import Foundation
import Network
import SystemConfiguration
import UniformTypeIdentifiers

public enum PeerTransportType: String, Sendable {
    case lan = "LAN"
    case mesh = "Mesh"
    case bluetooth = "Bluetooth"
    case none = "None"
}

public final class WebSocketServer: @unchecked Sendable {
    public var onClipboard: ((String) -> Void)?
    var onPrepareClipboardSync: (() -> Void)?
    public var onOpenURL: ((URL) -> Void)?
    public var onImage: ((ImagePayload) -> Void)?
    public var onFile: ((URL) -> Void)?
    public var onTransferStatus: ((String) -> Void)?
    var onTransferProgress: ((TransferProgress) -> Void)?
    public var onPeersChanged: (([Peer]) -> Void)?
    public var onLog: ((String) -> Void)?
    public var onLocalNetworkPermissionRequired: ((Bool) -> Void)?

    public var isBluetoothPermissionDenied: Bool {
        bluetoothConnector.isBluetoothPermissionDenied
    }

    /// Radio + permission reality for the status menu. Ad-hoc permission checks
    /// are unreliable in distribution, but the powered-on state is exact.
    public var isBluetoothPoweredOn: Bool {
        bluetoothConnector.isBluetoothPoweredOn
    }

    /// Call after the user re-enables Bluetooth so the peripheral re-advertises.
    public func bluetoothStateChanged() {
        bluetoothConnector.bluetoothStateChanged()
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
    private let transferQueue = DispatchQueue(label: "net.wastu.binderclip.file-transfers", qos: .utility)
    private let transferStore: FileTransferStore
    private var transferSessions: [ObjectIdentifier: ChunkedTransferSession] = [:]
    public var receivedFilesDirectory: URL { transferStore.receivedDirectory }
    private let rosterManager: RosterManager
    private let listenPort: UInt16
    private var bluetoothEnabled: Bool
    var onListening: ((UInt16) -> Void)?
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
    private var bluetoothRadioWatchTimer: DispatchSourceTimer?
    private var lifecycle = TransportLifecycle()
    private var wantsListener = false
    private var pairingAdvertisingUntil: Date?
    private var listenBackoffSeconds: TimeInterval = 1
    private var listenRestart: DispatchWorkItem?

    private var activeSessions: [ObjectIdentifier: WebSocketSession] = [:]
    private let clipboardOrdering: ClipboardOrdering
    private let clipboardCheckpointURL: URL
    private var clipboardText: String?
    private var clipboardImage: ImagePayload?
    private var clipboardEventSnapshot: String?
    private var lastBluetoothPoweredOn: Bool?

    private let stateLock = NSLock()
    private var cachedDeviceName: String
    private var cachedPeersSnapshot: [Peer] = []

    public var localDeviceID: String { rosterManager.localID }
    public var localDeviceName: String {
        stateLock.lock(); defer { stateLock.unlock() }
        return cachedDeviceName
    }

    public var localEndpoint: DirectEndpoint {
        DirectEndpoint(host: Self.localAddresses().first ?? "unknown", port: listenPort)
    }

    public convenience init() {
        let profile = AppProfile.current
        self.init(rosterManager: RosterManager(), bluetoothEnabled: profile.bluetoothEnabledByDefault || profile.defaults.bool(forKey: "debug-bluetooth-enabled"),
            receivedDirectory: FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first?.appendingPathComponent(profile.displayName, isDirectory: true))
    }

    init(rosterManager: RosterManager, port: UInt16 = SyncProtocol.defaultPort, bluetoothEnabled: Bool = AppProfile.current.bluetoothEnabledByDefault, receivedDirectory: URL? = nil) {
        self.rosterManager = rosterManager
        self.transferStore = FileTransferStore(root: rosterManager.stateDirectory.appendingPathComponent("file-transfers", isDirectory: true),
            receivedDirectory: receivedDirectory ?? rosterManager.stateDirectory.appendingPathComponent("received-files", isDirectory: true))
        self.clipboardCheckpointURL = rosterManager.stateDirectory.appendingPathComponent("clipboard-state.json")
        let checkpoint = (try? Data(contentsOf: clipboardCheckpointURL)).flatMap { try? JSONDecoder().decode(ClipboardCheckpoint.self, from: $0) }
        self.clipboardOrdering = ClipboardOrdering(localID: rosterManager.localID, checkpoint: checkpoint)
        self.clipboardEventSnapshot = checkpoint?.head.event
        self.listenPort = port
        self.bluetoothEnabled = bluetoothEnabled
        self.cachedDeviceName = rosterManager.localName
        self.cachedPeersSnapshot = rosterManager.peerSnapshot()
    }

    public var isBluetoothEnabled: Bool { queue.sync { bluetoothEnabled } }

    /// Opt-in debug acceptance mode. Disabling stops only this app's peripheral manager.
    public func setDebugBluetoothEnabled(_ enabled: Bool) {
        guard AppProfile.current.isDebug else { return }
        queue.async { [weak self] in
            guard let self, self.bluetoothEnabled != enabled else { return }
            self.bluetoothEnabled = enabled
            AppProfile.current.defaults.set(enabled, forKey: "debug-bluetooth-enabled")
            if self.lifecycle.active && enabled { self.bluetoothConnector.start(server: self) }
            if !enabled { self.bluetoothConnector.stop() }
            self.updateBluetoothDemand()
            self.publishPresence()
        }
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
            self.lifecycle.running = true
            self.startLocked()
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.lifecycle.running = false
            self.stopLocked()
        }
    }

    public func setSystemSleeping(_ sleeping: Bool) {
        queue.async { [weak self] in
            guard let self, self.lifecycle.sleeping != sleeping else { return }
            if sleeping { self.broadcastText(["type": "power", "state": "sleep"]) }
            self.lifecycle.sleeping = sleeping
            if sleeping { self.stopLocked() } else { self.startLocked() }
        }
    }

    private func startLocked() {
        guard lifecycle.active, !wantsListener else { return }
        rosterManager.markAllDisconnected()
        updateCachedState()
        wantsListener = true
        listenBackoffSeconds = 1
        startListener()
        startPathMonitor()
        startAddressWatch()
        if bluetoothEnabled { bluetoothConnector.start(server: self) }
        publishPresence()
    }

    private func stopLocked() {
        wantsListener = false
        listenRestart?.cancel(); listenRestart = nil
        pathDebounce?.cancel(); pathDebounce = nil
        pathMonitor?.cancel(); pathMonitor = nil
        stopAddressWatch()
        heartbeatTimer?.cancel(); heartbeatTimer = nil
        stopBluetoothRadioWatch()
        listener?.cancel(); listener = nil
        bluetoothConnector.stop()
        for session in activeSessions.values { session.close() }
        transferQueue.async { [weak self] in
            self?.transferSessions.values.forEach { $0.close() }
            self?.transferSessions.removeAll()
        }
        activeSessions.removeAll()
        rosterManager.markAllDisconnected()
        updateCachedState()
        publishPeers()
    }

    public func resetPairingKey() {
        queue.async { [weak self] in
            guard let self else { return }
            let sessions = Array(self.activeSessions.values)
            self.notifyUnpairAndDrop(sessions)
            _ = self.rosterManager.rotateGroupKey()
            self.clearClipboardPairing()
            self.clearFilePairing()
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
            self.clearClipboardPairing()
            self.clearFilePairing()
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
            if self.rosterManager.peers.isEmpty { self.clearClipboardPairing() }
            self.clearFilePairing(peerID: peerID)
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
                    self.handleConnectionClosed(session: session)
                }
            }
            queue.asyncAfter(deadline: .now() + 0.75) { [weak self] in
                guard let self, self.activeSessions[id] === session else { return }
                self.handleConnectionClosed(session: session)
            }
        }
    }

    public func createInvite() -> URL? {
        queue.sync {
            pairingAdvertisingUntil = SessionLiveness.now().addingTimeInterval(120)
            updateBluetoothDemand()
            queue.asyncAfter(deadline: .now() + 120) { [weak self] in self?.updateBluetoothDemand() }
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
        recordClipboard(text: text, image: nil, bootstrap: false, force: true, targetPeerID: targetDeviceId)
    }

    func observeClipboard(text: String, bootstrap: Bool) {
        recordClipboard(text: text, image: nil, bootstrap: bootstrap, force: !bootstrap)
    }

    func observeClipboard(image: ImagePayload, bootstrap: Bool) {
        recordClipboard(text: nil, image: image, bootstrap: bootstrap, force: !bootstrap)
    }

    func observeEmptyClipboard(bootstrap: Bool = false) {
        queue.async { [weak self] in
            guard let self else { return }
            self.clipboardText = nil; self.clipboardImage = nil
            if self.clipboardOrdering.observe(kind: "empty", hash: SyncProtocol.sha256Hex(Data()), bootstrap: bootstrap) != nil {
                self.saveClipboardCheckpoint()
                self.broadcastText(self.clipboardState())
            }
        }
    }

    private func recordClipboard(text: String?, image: ImagePayload?, bootstrap: Bool, force: Bool, targetPeerID: String? = nil) {
        guard text == nil || text!.utf8.count <= SyncProtocol.maximumTextBytes else { return }
        queue.async { [weak self] in
            guard let self else { return }
            let kind = image == nil ? "text" : "image"
            let hash = image?.sha256 ?? SyncProtocol.sha256Hex(text ?? "")
            let stamp = self.clipboardOrdering.observe(kind: kind, hash: hash, force: force, bootstrap: bootstrap)
            self.clipboardText = text; self.clipboardImage = image
            guard let stamp else { return }
            self.saveClipboardCheckpoint()
            // Initial content has an unknown copy time. Exchange it during catch-up instead of
            // publishing it as a new copy when the application happens to start.
            guard !bootstrap || stamp.time > 0 else { return }
            self.broadcastClipboard(stamp, targetPeerID: targetPeerID)
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
        recordClipboard(text: nil, image: image, bootstrap: false, force: true, targetPeerID: targetDeviceId)
    }

    public func canSendFile(to peerID: String) -> Bool {
        queue.sync { activeSessions.values.contains { $0.isAuthenticated && $0.peerID == peerID && $0.chunkedTransfers } }
    }

    public func sendFile(_ url: URL, targetDeviceID: String) {
        queue.async { [weak self] in
            guard let self, let session = self.activeSessions.values.first(where: {
                $0.isAuthenticated && $0.peerID == targetDeviceID && $0.chunkedTransfers
            }) else { return }
            let id = ObjectIdentifier(session.connection)
            self.transferQueue.async {
                do {
                    let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .contentTypeKey])
                    guard values.isRegularFile == true, values.isSymbolicLink != true,
                          let size = values.fileSize, size <= FileTransferProtocol.maximumFileBytes else { throw FileTransferError.invalidMetadata }
                    let input = try FileHandle(forReadingFrom: url)
                    defer { try? input.close() }
                    _ = try self.transferStore.stage(peerID: targetDeviceID, name: url.lastPathComponent,
                        mimeType: values.contentType?.preferredMIMEType ?? "application/octet-stream", kind: "file", input: input)
                    self.transferSessions[id]?.pump()
                } catch { self.transferStatus(L10n.tr("share_error_file")) }
            }
        }
    }

    private func transferStatus(_ status: String) { DispatchQueue.main.async { [weak self] in self?.onTransferStatus?(status) } }

    private func clearFilePairing(peerID: String? = nil) {
        transferQueue.async { [weak self] in
            guard let self else { return }
            let ids = self.transferSessions.filter { peerID == nil || $0.value.peerID == peerID }.map(\.key)
            for id in ids { self.transferSessions.removeValue(forKey: id)?.close() }
            self.transferStore.clearPairing(peerID: peerID)
        }
    }

    private func broadcastImage(_ image: ImagePayload, targetPeerID: String? = nil, excluding peerID: String? = nil, clipboard: ClipboardStamp? = nil) {
        for session in activeSessions.values where session.isAuthenticated {
            guard !SessionLiveness.shouldEvict(boundLocal: session.boundLocalAddress, currentLocals: lastLocalAddresses) else { continue }
            guard session.peerID != peerID, targetPeerID == nil || session.peerID == targetPeerID else { continue }
            if session.chunkedTransfers, let remoteID = session.peerID {
                let id = ObjectIdentifier(session.connection)
                transferQueue.async { [weak self] in
                    guard let self else { return }
                    do {
                        _ = try self.transferStore.stageImage(peerID: remoteID, image: image, clipboard: clipboard)
                        self.transferSessions[id]?.pump()
                    } catch { self.transferStatus(L10n.tr("transfer_failed")) }
                }
            } else { session.sendBinary(SyncProtocol.packImage(image: image, originId: localDeviceID, clipboard: clipboard)) }
        }
    }

    private func attachTransfers(_ session: WebSocketSession, peerID: String) {
        let id = ObjectIdentifier(session.connection)
        transferQueue.async { [weak self, weak session] in
            guard let self, let session else { return }
            let engine = ChunkedTransferSession(peerID: peerID, store: self.transferStore, queue: self.transferQueue,
                sendText: { [weak self, weak session] object in
                    self?.queue.async { [weak self, weak session] in
                        guard let self, let session, self.activeSessions[id] === session else { return }
                        session.sendText(object)
                    }
                }, sendBinary: { [weak self, weak session] data in
                    self?.queue.async { [weak self, weak session] in
                        guard let self, let session, self.activeSessions[id] === session else { return }
                        session.sendBinary(data)
                    }
                }, disconnect: { [weak self, weak session] in
                    self?.queue.async { [weak self, weak session] in
                        guard let session else { return }
                        self?.handleConnectionClosed(session: session)
                    }
                }, onImage: { [weak self] image in
                    self?.queue.async { [weak self] in self?.receivedClipboard(text: nil, image: image, stamp: nil, peerID: peerID, reply: { _ in }) }
                }, onFile: { [weak self] file in
                    DispatchQueue.main.async { self?.onFile?(file.file) }
                }, onStatus: { [weak self] status in self?.transferStatus(status) },
                onProgress: { [weak self] progress in
                    DispatchQueue.main.async { self?.onTransferProgress?(progress) }
                }, onOrderedImage: { [weak self, weak session] image, stamp in
                    self?.queue.async { [weak self, weak session] in
                        guard let self, let session, self.activeSessions[id] === session else { return }
                        self.receivedClipboard(text: nil, image: image, stamp: stamp, peerID: peerID, reply: { session.sendText($0) })
                    }
                })
            self.transferSessions[id] = engine
            engine.pump()
        }
    }

    private func withCurrentClipboard(_ work: @escaping () -> Void) {
        guard let prepare = onPrepareClipboardSync else { work(); return }
        DispatchQueue.main.async { [weak self] in
            prepare()
            self?.queue.async(execute: work)
        }
    }

    private func clearClipboardPairing() {
        clipboardOrdering.reset(); clipboardText = nil; clipboardImage = nil
        stateLock.lock(); clipboardEventSnapshot = nil; stateLock.unlock()
        try? FileManager.default.removeItem(at: clipboardCheckpointURL)
    }

    private func saveClipboardCheckpoint() {
        stateLock.lock(); clipboardEventSnapshot = clipboardOrdering.head?.event; stateLock.unlock()
        guard let value = clipboardOrdering.checkpoint, let data = try? JSONEncoder().encode(value) else { return }
        do {
            try FileManager.default.createDirectory(at: clipboardCheckpointURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: clipboardCheckpointURL, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: clipboardCheckpointURL.path)
        } catch { onLog?("Could not save clipboard version") }
    }

    private func isCurrentClipboardEvent(_ event: String) -> Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return clipboardEventSnapshot == event
    }

    private func clipboardState() -> [String: Any] {
        var json: [String: Any] = ["type": "clipboard_state"]
        if let head = clipboardOrdering.head { json.merge(head.fields) { _, new in new } }
        let available = clipboardOrdering.head?.kind == "empty" ||
            (clipboardText.map(SyncProtocol.sha256Hex) == clipboardOrdering.head?.hash && clipboardText != nil) ||
            (clipboardImage?.sha256 == clipboardOrdering.head?.hash && clipboardImage != nil)
        json["clipAvailable"] = available ? 1 : 0
        return json
    }

    private func broadcastClipboard(_ stamp: ClipboardStamp, targetPeerID: String? = nil, excluding peerID: String? = nil) {
        if stamp.kind == "text", let text = clipboardText, SyncProtocol.sha256Hex(text) == stamp.hash {
            var payload = stamp.fields
            payload.merge(["type": "clipboard", "text": text, "hash": stamp.hash, "eventId": stamp.event,
                           "originId": stamp.origin, "timestamp": stamp.time]) { _, new in new }
            broadcastText(payload, targetPeerId: targetPeerID, excludePeerID: peerID)
        } else if stamp.kind == "image", let image = clipboardImage, image.sha256 == stamp.hash {
            broadcastImage(image, targetPeerID: targetPeerID, excluding: peerID, clipboard: stamp)
        } else {
            broadcastText(clipboardState(), targetPeerId: targetPeerID, excludePeerID: peerID)
        }
    }

    private func sendClipboardSnapshot(peerID: String, images: Bool, reply: ([String: Any]) -> Void) {
        guard let head = clipboardOrdering.head else { reply(clipboardState()); return }
        if head.kind == "text", let text = clipboardText, SyncProtocol.sha256Hex(text) == head.hash {
            var json = head.fields; json["type"] = "clipboard"; json["text"] = text
            reply(json)
        } else if head.kind == "image", images, let image = clipboardImage, image.sha256 == head.hash {
            broadcastImage(image, targetPeerID: peerID, clipboard: head)
        } else { reply(clipboardState()) }
    }

    private func handleClipboardControl(_ json: [String: Any], peerID: String, images: Bool, reply: @escaping ([String: Any]) -> Void) {
        withCurrentClipboard { [weak self] in
            guard let self, self.rosterManager.peers[peerID] != nil else { return }
            if json["type"] as? String == "clipboard_request" {
                if json["requestEvent"] as? String == self.clipboardOrdering.head?.event {
                    self.sendClipboardSnapshot(peerID: peerID, images: images, reply: reply)
                } else { reply(self.clipboardState()) }
                return
            }
            guard let remote = try? ClipboardStamp.parse(json) else {
                self.sendClipboardSnapshot(peerID: peerID, images: images, reply: reply)
                return
            }
            if remote == self.clipboardOrdering.head {
                if self.clipboardState()["clipAvailable"] as? Int == 0 && (json["clipAvailable"] as? Int ?? 1) == 1 && remote.kind != "empty" {
                    if images || remote.kind == "text" { reply(["type": "clipboard_request", "requestEvent": remote.event]) }
                } else { reply(self.clipboardState()) }
                return
            }
            if let local = self.clipboardOrdering.head, local >= remote {
                self.sendClipboardSnapshot(peerID: peerID, images: images, reply: reply)
            } else if remote.kind == "empty" || remote.fingerprint == self.clipboardOrdering.head?.fingerprint {
                self.clipboardOrdering.receive(remote)
                if remote.kind == "empty" { self.clipboardText = nil; self.clipboardImage = nil }
                self.saveClipboardCheckpoint()
                self.broadcastText(self.clipboardState(), excludePeerID: peerID)
                if remote.kind != "empty", self.clipboardState()["clipAvailable"] as? Int == 0,
                   (json["clipAvailable"] as? Int ?? 1) == 1, images || remote.kind == "text" {
                    reply(["type": "clipboard_request", "requestEvent": remote.event])
                } else { reply(self.clipboardState()) }
            } else if (json["clipAvailable"] as? Int ?? 1) == 1 && (images || remote.kind == "text") {
                reply(["type": "clipboard_request", "requestEvent": remote.event])
            }
        }
    }

    private func receivedClipboard(text: String?, image: ImagePayload?, stamp: ClipboardStamp?, peerID: String, reply: @escaping ([String: Any]) -> Void) {
        guard text == nil || text!.utf8.count <= SyncProtocol.maximumTextBytes else { return }
        let hash = image?.sha256 ?? SyncProtocol.sha256Hex(text ?? "")
        let kind = image == nil ? "text" : "image"
        guard stamp == nil || (stamp?.hash == hash && stamp?.kind == kind) else { return }
        withCurrentClipboard { [weak self] in
            guard let self, self.rosterManager.peers[peerID] != nil else { return }
            let accepted: ClipboardStamp
            let changed: Bool
            let missingPayload = self.clipboardText == nil && self.clipboardImage == nil
            if let stamp {
                changed = self.clipboardOrdering.receive(stamp) || missingPayload
                guard self.clipboardOrdering.head == stamp else { reply(self.clipboardState()); return }
                accepted = stamp
            } else {
                guard let fresh = self.clipboardOrdering.observe(kind: kind, hash: hash, force: true, origin: peerID) else { return }
                accepted = fresh; changed = true
            }
            self.clipboardText = text; self.clipboardImage = image
            self.saveClipboardCheckpoint()
            if changed {
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.isCurrentClipboardEvent(accepted.event) else { return }
                    if let image { self.onImage?(image) } else if let text { self.onClipboard?(text) }
                }
                self.broadcastClipboard(accepted, excluding: peerID)
            } else { self.broadcastText(self.clipboardState(), excludePeerID: peerID) }
            reply(self.clipboardState())
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
            wsOptions.maximumMessageSize = SyncProtocol.maximumImageBytes + 24
            wsOptions.setClientRequestHandler(queue) { [weak self] _, _ in
                self?.onLog?("Accepted WebSocket handshake")
                return NWProtocolWebSocket.Response(status: .accept, subprotocol: nil)
            }

            let params = NWParameters(tls: nil, tcp: tcpOptions)
            params.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)
            params.allowLocalEndpointReuse = true

            let port: NWEndpoint.Port = listenPort == 0 ? .any : NWEndpoint.Port(rawValue: listenPort)!
            let listener = try NWListener(using: params, on: port)

            listener.service = Self.makeBonjourService(
                deviceID: localDeviceID,
                deviceName: localDeviceName
            )

            listener.stateUpdateHandler = { [weak self, weak listener] state in
                guard let self, self.listener === listener, self.lifecycle.active else { return }
                switch state {
                case .ready:
                    self.listenBackoffSeconds = 1
                    if let port = listener?.port { self.onListening?(port.rawValue) }
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
        guard lifecycle.active, wantsListener else { nwConnection.forceCancel(); return }
        let session = WebSocketSession(connection: nwConnection)
        let id = ObjectIdentifier(nwConnection)
        activeSessions[id] = session
        print("[WebSocketServer] Accepting incoming connection: \(nwConnection.endpoint)")
        fflush(stdout)

        nwConnection.stateUpdateHandler = { [weak self, weak session] state in
            guard let self, let session, self.lifecycle.active, self.activeSessions[id] === session else { return }
            print("[WebSocketServer] Connection \(session.connection.endpoint) state: \(state)")
            fflush(stdout)
            switch state {
            case .ready:
                self.bindSession(session)
                self.scheduleAuthDeadline(session)
                session.sendHandshake()
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

        scheduleAuthDeadline(session)
        nwConnection.start(queue: queue)
    }

    private func handleConnectionClosed(session: WebSocketSession) {
        let id = ObjectIdentifier(session.connection)
        let wasActive = activeSessions.removeValue(forKey: id) != nil
        transferQueue.async { [weak self] in self?.transferSessions.removeValue(forKey: id)?.close() }
        session.close()
        guard wasActive else { return }
        publishPresence()
    }

    private func scheduleAuthDeadline(_ session: WebSocketSession) {
        session.authDeadline?.cancel()
        let work = DispatchWorkItem { [weak self, weak session] in
            guard let self, let session else { return }
            let id = ObjectIdentifier(session.connection)
            guard self.activeSessions[id] === session, !session.isAuthenticated else { return }
            self.onLog?("Auth deadline exceeded")
            self.handleConnectionClosed(session: session)
        }
        session.authDeadline = work
        queue.asyncAfter(deadline: .now() + SyncProtocol.authDeadline, execute: work)
    }

    private func usableAuthenticatedPeerIDs() -> [String] {
        let now = SessionLiveness.now()
        let locals = lastLocalAddresses
        var peerIDs = activeSessions.values.compactMap { session -> String? in
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
        // Include Bluetooth-authenticated peers using the lock-protected snapshot
        // (avoids a cross-queue queue.sync that could deadlock with the BT connector queue).
        for peerID in bluetoothConnector.authenticatedPeerIDSnapshot {
            peerIDs.append(peerID)
        }
        return peerIDs
    }

    private func publishPresence() {
        for peerID in rosterManager.peers.keys {
            let connected = PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: usableAuthenticatedPeerIDs())
            rosterManager.setPeerConnected(peerID, connected: connected)
        }
        updateCachedState()
        publishPeers()
        if lifecycle.active && !livePeerIDs.isEmpty { startHeartbeat() }
        else { heartbeatTimer?.cancel(); heartbeatTimer = nil }
    }

    private func updateBluetoothDemand() {
        let connectedWS = Set(activeSessions.values.compactMap { $0.isAuthenticated ? $0.peerID : nil })
        let missingPeers = Set(rosterManager.peers.keys).subtracting(connectedWS)
        let pairing = pairingAdvertisingUntil.map { $0 > SessionLiveness.now() } ?? false
        let needed = bluetoothEnabled && lifecycle.active && (pairing || !missingPeers.isEmpty)
        bluetoothConnector.setNeeded(needed)
        if needed { startBluetoothRadioWatch() } else { stopBluetoothRadioWatch() }
    }

    private func readNextMessage(from session: WebSocketSession) {
        guard lifecycle.active, activeSessions[ObjectIdentifier(session.connection)] === session else { return }
        session.connection.receiveMessage { [weak self, weak session] data, context, isComplete, error in
            guard let self, let session, self.lifecycle.active, self.activeSessions[ObjectIdentifier(session.connection)] === session else { return }
            let opcode = (context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata)?.opcode
            if error != nil || opcode == .close || (data == nil && context == nil && isComplete) {
                self.handleConnectionClosed(session: session)
                return
            }

            if let data, !data.isEmpty {
                let isBinary = (context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata)?.opcode == .binary
                if isBinary {
                    do {
                        guard let secure = session.secure else { throw SecureChannelError.invalidHandshake }
                        self.handleBinaryMessage(try secure.open(data, kind: 2), from: session)
                    } catch { self.handleConnectionClosed(session: session) }
                } else {
                    self.handleWireText(data, session: session)
                }
            }

            self.readNextMessage(from: session)
        }
    }

    private func handleWireText(_ data: Data, session: WebSocketSession) {
        do {
            guard data.count <= (session.isAuthenticated ? 8 * SyncProtocol.maximumTextBytes + 16_384 : 16_384),
                  let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw SecureChannelError.invalidFrame }
            if object["type"] as? String == "secure_reply", session.secure == nil {
                guard let nonce = object["nonce"] as? String, let clientNonce = Data(base64Encoded: nonce),
                      let proof = object["proof"] as? String, let proofBytes = Data(base64Encoded: proof) else { throw SecureChannelError.invalidHandshake }
                session.secure = try session.handshake.accept(clientNonce: clientNonce, proof: proofBytes, psk: rosterManager.groupKey, transport: "ws")
                return
            }
            guard object["type"] as? String == "secure", let encoded = object["data"] as? String,
                  let packet = Data(base64Encoded: encoded), let secure = session.secure else { throw SecureChannelError.invalidFrame }
            handleTextMessage(try secure.open(packet, kind: 1), from: session)
        } catch {
            onLog?("Encrypted WebSocket handshake/frame rejected; both apps must support protocol 3")
            handleConnectionClosed(session: session)
        }
    }

    private func handleTextMessage(_ data: Data, from session: WebSocketSession) {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }
        session.lastHeard = SessionLiveness.now()

        switch type {
        case "auth", "hello":
            handleAuthMessage(json, session: session)

        case "clipboard":
            guard session.isAuthenticated, let peerID = session.peerID, let text = json["text"] as? String else { return }
            guard let stamp = try? ClipboardStamp.parse(json) else {
                if session.clipboardSync && json["shared"] as? Int != 1 { return }
                receivedClipboard(text: text, image: nil, stamp: nil, peerID: peerID, reply: { session.sendText($0) })
                return
            }
            receivedClipboard(text: text, image: nil, stamp: stamp, peerID: peerID, reply: { session.sendText($0) })

        case "clipboard_state", "clipboard_request":
            guard session.isAuthenticated, session.clipboardSync, let peerID = session.peerID else { return }
            handleClipboardControl(json, peerID: peerID, images: true, reply: { session.sendText($0) })

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
            guard session.isAuthenticated else { return }
            session.sendText(["type": "pong", "t": json["t"] as Any])

        case "pong":
            break

        case "power":
            guard session.isAuthenticated else { return }
            applyPowerState(json["state"] as? String, session: session)

        case "transfer_offer", "transfer_ack", "transfer_done", "transfer_cancel":
            guard session.isAuthenticated, session.chunkedTransfers else { handleConnectionClosed(session: session); return }
            let id = ObjectIdentifier(session.connection)
            transferQueue.async { [weak self] in self?.transferSessions[id]?.handle(json) }

        default:
            break
        }
    }

    private func handleAuthMessage(_ json: [String: Any], session: WebSocketSession) {
        let clockReceive = Int64(Date().timeIntervalSince1970 * 1000)
        guard session.secure != nil,
              let clientID = json["deviceId"] as? String,
              let clientName = json["deviceName"] as? String,
              json["version"] as? Int == SyncProtocol.version else {
            handleConnectionClosed(session: session)
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
        session.chunkedTransfers = json[FileTransferProtocol.capability] as? Int == FileTransferProtocol.version
        session.clipboardSync = json[ClipboardStamp.capability] as? Int == 1
        session.livenessBudget = SyncProtocol.heartbeatBudget

        for (id, other) in activeSessions where id != incomingID && PeerPresence.shouldCancelExtra(
            isAuthenticated: other.isAuthenticated,
            existingPeerID: other.peerID,
            incomingPeerID: clientID
        ) {
            handleConnectionClosed(session: other)
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
            FileTransferProtocol.capability: FileTransferProtocol.version,
            ClipboardStamp.capability: 1,
            "clockProbe": json["clockProbe"] as? Int64 ?? 0,
            "clockReceive": clockReceive,
            "clockNow": Int64(Date().timeIntervalSince1970 * 1000),
            "endpoints": advertisedEndpointList()
        ])
        if session.chunkedTransfers { attachTransfers(session, peerID: clientID) }
        if session.clipboardSync {
            withCurrentClipboard { [weak self, weak session] in
                guard let self, let session, self.activeSessions[incomingID] === session else { return }
                session.sendText(self.clipboardState())
            }
        }
        onLog?("Connected to \(clientName)")
    }

    private func handleBinaryMessage(_ data: Data, from session: WebSocketSession) {
        guard session.isAuthenticated else { return }
        session.lastHeard = SessionLiveness.now()
        if FileTransferProtocol.isChunk(data) {
            guard session.chunkedTransfers, data.count <= FileTransferProtocol.headerBytes + FileTransferProtocol.chunkBytes else {
                handleConnectionClosed(session: session); return
            }
            let id = ObjectIdentifier(session.connection)
            transferQueue.async { [weak self] in self?.transferSessions[id]?.handleChunk(data) }
            return
        }
        guard let (meta, imgData) = SyncProtocol.unpackImage(data),
              let image = try? ImagePayload(id: UUID(uuidString: meta.id) ?? UUID(), mimeType: meta.mimeType, data: imgData) else {
            return
        }

        if let peerID = session.peerID {
            receivedClipboard(text: nil, image: image, stamp: meta.clipboard, peerID: peerID, reply: { session.sendText($0) })
        }
    }

    private func broadcastText(_ object: [String: Any], targetPeerId: String? = nil, excludeSessionId: ObjectIdentifier? = nil, excludePeerID: String? = nil) {
        let locals = lastLocalAddresses
        for (id, session) in activeSessions where session.isAuthenticated {
            if let excludeSessionId, id == excludeSessionId { continue }
            if let targetPeerId, session.peerID != targetPeerId { continue }
            if let excludePeerID, session.peerID == excludePeerID { continue }
            if SessionLiveness.shouldEvict(boundLocal: session.boundLocalAddress, currentLocals: locals) { continue }
            session.sendText(object)
        }
        for session in bluetoothConnector.authenticatedSessions {
            if let targetPeerId, session.peerID != targetPeerId { continue }
            if let excludePeerID, session.peerID == excludePeerID { continue }
            if let fields = Self.btFields(from: object) {
                session.sendFrame(fields)
            }
        }
    }

    /// Canonical CBOR field order for frames mirrored onto the Bluetooth link.
    static func btFields(from object: [String: Any]) -> [(String, BtValue)]? {
        guard let type = object["type"] as? String else { return nil }
        switch type {
        case "clipboard", "clipboard_state", "clipboard_request":
            var fields: [(String, BtValue)] = [("type", .text(type))]
            if type == "clipboard" { fields.append(("text", .text(object["text"] as? String ?? ""))) }
            if let event = object["requestEvent"] as? String { fields.append(("requestEvent", .text(event))) }
            if let available = object["clipAvailable"] as? Int { fields.append(("clipAvailable", .uint(UInt64(max(0, available))))) }
            if let stamp = try? ClipboardStamp.parse(object) {
                fields.append(contentsOf: [
                    ("clipTime", .uint(UInt64(stamp.time))), ("clipCounter", .uint(UInt64(stamp.counter))),
                    ("clipOrigin", .text(stamp.origin)), ("clipEvent", .text(stamp.event)),
                    ("clipKind", .text(stamp.kind)), ("clipHash", .text(stamp.hash)),
                ])
            }
            return fields
        case "openUrl":
            guard let url = object["url"] as? String else { return nil }
            return [("type", .text(type)), ("url", .text(url))]
        case "ping", "pong":
            return [("type", .text(type)), ("t", .uint(UInt64(object["t"] as? Int64 ?? 0)))]
        case "power":
            return [("type", .text(type)), ("state", .text(object["state"] as? String ?? "awake"))]
        case "unpair":
            return [("type", .text(type))]
        case "rename":
            return [("type", .text(type)), ("id", .text(object["id"] as? String ?? "")), ("name", .text(object["name"] as? String ?? ""))]
        default:
            return nil
        }
    }

    /// Watch the Bluetooth radio and re-arm the peripheral when the user toggles it.
    /// CoreBluetooth only surfaces the state change on a *fresh* manager, so a disabled
    /// radio at launch (or one turned off later) would otherwise never recover.
    private func startBluetoothRadioWatch() {
        guard bluetoothRadioWatchTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 15, repeating: 15, leeway: .seconds(3))
        timer.setEventHandler { [weak self] in
            self?.tickBluetoothRadioWatch()
        }
        timer.resume()
        bluetoothRadioWatchTimer = timer
    }

    private func stopBluetoothRadioWatch() {
        bluetoothRadioWatchTimer?.cancel()
        bluetoothRadioWatchTimer = nil
    }

    private func tickBluetoothRadioWatch() {
        let on = bluetoothConnector.isBluetoothPoweredOn
        if lastBluetoothPoweredOn == nil {
            lastBluetoothPoweredOn = on
            return
        }
        if on != lastBluetoothPoweredOn {
            lastBluetoothPoweredOn = on
            onLog?(on ? "Bluetooth enabled — BinderClip peripheral (re)advertising"
                       : "Bluetooth disabled — Bluetooth fallback unavailable")
            if on {
                bluetoothConnector.bluetoothStateChanged()
            }
        } else if !on {
            // Radio reads off: re-create the peripheral manager (throttled) so that when the user
            // re-enables Bluetooth, a fresh manager observes `.poweredOn` and re-advertises.
            bluetoothConnector.retryPeripheralIfOff()
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
        guard lifecycle.active else { return }
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
        sampler.schedule(deadline: .now() + 30, repeating: 30, leeway: .seconds(5))
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
        queue.async { [weak self] in
            guard let self, self.lifecycle.active else { return }
            self.addressDebounce?.cancel()
            let work = DispatchWorkItem { [weak self] in self?.refreshAddresses(reason: "interfaces") }
            self.addressDebounce = work
            self.queue.asyncAfter(deadline: .now() + 0.2, execute: work)
        }
    }

    private func refreshAddresses(reason: String) {
        guard lifecycle.active else { return }
        EnergyCounters.add("network.address.sample")
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
            handleConnectionClosed(session: session)
        }
    }

    private func bindSession(_ session: WebSocketSession) {
        let remote = session.remoteHostString() ?? ""
        session.boundLocalAddress = SessionLiveness.boundLocalAddress(remote: remote, localAddresses: lastLocalAddresses)
        session.lastHeard = SessionLiveness.now()
    }

    private func startHeartbeat() {
        guard lifecycle.active, heartbeatTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + SyncProtocol.heartbeatInterval, repeating: SyncProtocol.heartbeatInterval, leeway: .milliseconds(200))
        timer.setEventHandler { [weak self] in
            self?.tickHeartbeat()
        }
        timer.resume()
        heartbeatTimer = timer
    }

    private func tickHeartbeat() {
        guard lifecycle.active else { return }
        let now = SessionLiveness.now()
        let locals = lastLocalAddresses
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
                handleConnectionClosed(session: session)
                evicted = true
                continue
            }
            guard session.nextPing <= now else { continue }
            EnergyCounters.add("heartbeat.sent")
            session.nextPing = now.addingTimeInterval(session.livenessBudget == SyncProtocol.heartbeatBudget ? SyncProtocol.heartbeatInterval : SessionLiveness.idleHeartbeatInterval)
            session.sendText([
                "type": "ping",
                "t": Int64(Date().timeIntervalSince1970 * 1000)
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
                onLog?("Bluetooth liveness deadline exceeded (budget=\(Int(session.livenessBudget))s)")
                session.close()
                evicted = true
                continue
            }
            guard session.nextPing <= now else { continue }
            EnergyCounters.add("heartbeat.sent")
            session.nextPing = now.addingTimeInterval(session.livenessBudget == SyncProtocol.heartbeatBudget ? SyncProtocol.heartbeatInterval : SessionLiveness.idleHeartbeatInterval)
            session.sendFrame([
                ("type", .text("ping")),
                ("t", .uint(UInt64(Int64(Date().timeIntervalSince1970 * 1000))))
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
            guard let self, self.lifecycle.active, self.bluetoothConnector.isCurrentSession(session) else { return }
            do {
                let type = fields.first(where: { $0.0 == "type" })?.1.valueAsText
                if type == "secure_reply", session.secure == nil {
                    guard let nonce = fields.first(where: { $0.0 == "nonce" })?.1.valueAsText,
                          let clientNonce = Data(base64Encoded: nonce),
                          let proof = fields.first(where: { $0.0 == "proof" })?.1.valueAsText,
                          let proofBytes = Data(base64Encoded: proof) else { throw SecureChannelError.invalidHandshake }
                    session.secure = try session.handshake.accept(clientNonce: clientNonce, proof: proofBytes, psk: self.rosterManager.groupKey, transport: "ble")
                    return
                }
                guard type == "secure", let encoded = fields.first(where: { $0.0 == "data" })?.1.valueAsText,
                      let packet = Data(base64Encoded: encoded), let secure = session.secure else { throw SecureChannelError.invalidFrame }
                let decoded = try BtCbor.decode(Array(secure.open(packet, kind: 3)))
                self.handleBluetoothFrameLocked(decoded, session: session)
            } catch { self.bluetoothConnector.dropSession(session) }
        }
    }

    func noteBluetoothProgress(_ session: BluetoothSession) {
        queue.async { [weak self] in
            guard let self, self.lifecycle.active, session.isAuthenticated, self.bluetoothConnector.isCurrentSession(session) else { return }
            session.lastHeard = SessionLiveness.now()
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
        if session.isAuthenticated { session.lastHeard = SessionLiveness.now() }
        switch type {
        case "auth":
            let clockReceive = Int64(Date().timeIntervalSince1970 * 1000)
            guard !session.isAuthenticated else { return }
            let clientID = text("deviceId") ?? ""
            let clientName = text("deviceName") ?? "Android"
            guard session.secure != nil, uint("version") == UInt64(SyncProtocol.version),
                  rosterManager.shouldAcceptPeer(clientID, isPairingScan: false) else {
                onLog?("Unauthorized Bluetooth connection rejected")
                bluetoothConnector.dropSession(session)
                return
            }
            session.isAuthenticated = true
            session.clipboardSync = uint(ClipboardStamp.capability) == 1
            session.peerID = clientID
            session.peerName = clientName
            session.livenessBudget = SyncProtocol.heartbeatBudget
            session.lastHeard = SessionLiveness.now()
            startHeartbeat()
            session.cancelAuthDeadline()
            self.bluetoothConnector.registerBluetoothMapping(btName: session.deviceName, peerID: clientID)
            // Promote pending → active so the session is visible to broadcasts and heartbeats
            // immediately instead of only after the phone's next frame.
            self.bluetoothConnector.promoteSession(session)

            // A late fallback handshake must retain a working preferred WebSocket.
            // Android releases Bluetooth after WebSocket's stability grace period.
            let peer = Peer(id: clientID, name: clientName, endpoint: DirectEndpoint(host: "bluetooth", port: 0), connected: true, platform: "Android")
            _ = rosterManager.addOrUpdatePeer(peer)
            publishPresence()

            let okFields: [(String, BtValue)] = [
                ("type", .text("auth_ok")),
                ("deviceId", .text(localDeviceID)),
                ("deviceName", .text(localDeviceName)),
                ("version", .uint(UInt64(SyncProtocol.version))),
                (ClipboardStamp.capability, .uint(1)),
                ("clockProbe", .uint(uint("clockProbe") ?? 0)),
                ("clockReceive", .uint(UInt64(clockReceive))),
                ("clockNow", .uint(UInt64(Date().timeIntervalSince1970 * 1000))),
                ("endpoints", .array(advertisedEndpointList())),
            ]
            session.sendFrame(okFields)
            if session.clipboardSync {
                withCurrentClipboard { [weak self, weak session] in
                    guard let self, let session, session.isAuthenticated, let fields = Self.btFields(from: self.clipboardState()) else { return }
                    session.sendFrame(fields)
                }
            }
            onLog?("Connected to \(clientName) over Bluetooth")
        case "clipboard", "clipboard_state", "clipboard_request":
            guard session.isAuthenticated, let peerID = session.peerID else { return }
            var json: [String: Any] = [:]
            for (key, value) in fields {
                switch value {
                case .text(let text): json[key] = text
                case .uint(let number): if number <= UInt64(Int64.max) { json[key] = Int64(number) }
                case .array(let values): json[key] = values
                }
            }
            let reply: ([String: Any]) -> Void = { value in
                if let fields = Self.btFields(from: value) { session.sendFrame(fields) }
            }
            if type == "clipboard", let body = text("text") {
                let stamp = try? ClipboardStamp.parse(json)
                guard stamp != nil || !session.clipboardSync || uint("shared") == 1 else { return }
                receivedClipboard(text: body, image: nil, stamp: stamp, peerID: peerID, reply: reply)
            } else if session.clipboardSync {
                handleClipboardControl(json, peerID: peerID, images: false, reply: reply)
            }
        case "openUrl":
            guard session.isAuthenticated, let url = text("url"), let value = URL(string: url) else { return }
            session.lastHeard = SessionLiveness.now()
            print("[WebSocketServer] Bluetooth browser link received")
            DispatchQueue.main.async { [weak self] in
                self?.onOpenURL?(value)
            }
        case "ping":
            guard session.isAuthenticated else { return }
            session.lastHeard = SessionLiveness.now()
            session.sendFrame([("type", .text("pong")), ("t", .uint(uint("t") ?? 0))])
        case "pong":
            guard session.isAuthenticated else { return }
            session.lastHeard = SessionLiveness.now()
        case "power":
            guard session.isAuthenticated else { return }
            session.lastHeard = SessionLiveness.now()
            let sleeping = text("state") == "sleep"
            session.livenessBudget = sleeping ? SyncProtocol.heartbeatSleepBudget : SyncProtocol.heartbeatBudget
            session.nextPing = SessionLiveness.nextPingAfterPowerChange(sleeping: sleeping, now: SessionLiveness.now())
        default:
            break
        }
    }

    private func applyPowerState(_ state: String?, session: WebSocketSession) {
        switch state {
        case "sleep":
            session.livenessBudget = SyncProtocol.heartbeatSleepBudget
            session.nextPing = SessionLiveness.nextPingAfterPowerChange(sleeping: true, now: SessionLiveness.now())
        case "awake":
            session.livenessBudget = SyncProtocol.heartbeatBudget
            session.nextPing = SessionLiveness.nextPingAfterPowerChange(sleeping: false, now: SessionLiveness.now())
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
            type: AppProfile.current.bonjourType,
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
        updateBluetoothDemand()
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
    let handshake = SecureHandshake()
    var secure: SecureChannel?
    var isAuthenticated: Bool = false
    var chunkedTransfers: Bool = false
    var clipboardSync: Bool = false
    var peerID: String?
    var peerName: String?
    var boundLocalAddress: String?
    var lastHeard: Date?
    var livenessBudget: TimeInterval = SyncProtocol.heartbeatBudget
    var nextPing = SessionLiveness.now()
    var authDeadline: DispatchWorkItem?

    init(connection: NWConnection) {
        self.connection = connection
    }

    func close() {
        authDeadline?.cancel()
        authDeadline = nil
        connection.stateUpdateHandler = nil
        connection.forceCancel()
    }

    func sendText(_ object: [String: Any], onComplete: (() -> Void)? = nil) {
        do {
            guard let secure else { throw SecureChannelError.invalidHandshake }
            let plaintext = try JSONSerialization.data(withJSONObject: object)
            let packet = try secure.seal(plaintext, kind: 1)
            let data = try JSONSerialization.data(withJSONObject: ["type": "secure", "data": packet.base64EncodedString()])
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

    func sendBinary(_ plaintext: Data) {
        guard let secure, let data = try? secure.seal(plaintext, kind: 2) else { connection.forceCancel(); return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binary", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { error in
            if let error {
                print("[WebSocketSession] sendBinary error: \(error)")
            }
        })
    }

    func sendHandshake() {
        guard let data = try? JSONSerialization.data(withJSONObject: ["type": "secure_hello", "version": SyncProtocol.version, "nonce": handshake.nonce.base64EncodedString()]) else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "secure-handshake", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
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
