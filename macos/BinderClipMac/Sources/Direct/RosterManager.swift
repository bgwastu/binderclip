import Foundation
import Security

public struct DirectEndpoint: Codable, Hashable, Sendable {
    public let host: String
    public let port: UInt16

    public init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }
}

public struct Peer: Codable, Hashable, Identifiable, Sendable {
    public let id: String
    public var name: String
    public var endpoint: DirectEndpoint
    public var connected: Bool
    public var platform: String

    public init(id: String, name: String, endpoint: DirectEndpoint, connected: Bool, platform: String = "Android") {
        self.id = id
        self.name = name
        self.endpoint = endpoint
        self.connected = connected
        self.platform = platform
    }

    enum CodingKeys: String, CodingKey { case id, name, endpoint, connected, platform }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        endpoint = try values.decode(DirectEndpoint.self, forKey: .endpoint)
        connected = try values.decode(Bool.self, forKey: .connected)
        platform = try values.decodeIfPresent(String.self, forKey: .platform) ?? "Android"
    }
}

/// Small owner-only file store for pairing secrets. Avoids Keychain ACL prompts.
final class PrivateStateStore: @unchecked Sendable {
    private let file: URL

    static func applicationSupportDirectory() -> URL {
        AppProfile.current.stateDirectory
    }

    init(directory: URL = applicationSupportDirectory()) {
        let manager = FileManager.default
        try? manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try? manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        file = directory.appendingPathComponent("direct-secrets.json")
    }

    func data(account: String) -> Data? {
        guard let stored = contents()[account] else { return nil }
        return Data(base64Encoded: stored)
    }

    func set(_ data: Data, account: String) {
        var values = contents()
        values[account] = data.base64EncodedString()
        guard let encoded = try? JSONEncoder().encode(values) else { return }
        try? encoded.write(to: file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }

    private func contents() -> [String: String] {
        guard let data = try? Data(contentsOf: file),
              let values = try? JSONDecoder().decode([String: String].self, from: data) else {
            return [:]
        }
        return values
    }
}

/// Manages identity, peer roster, and the shared pairing key.
final class RosterManager: @unchecked Sendable {
    let stateDirectory: URL
    private let secureStore: PrivateStateStore
    private let defaults: UserDefaults
    private(set) var localID: String
    private(set) var localName: String
    private(set) var groupKey: Data
    private(set) var peers: [String: Peer] = [:]
    private var unpairedIDs: Set<String> = []

    init(stateDirectory: URL = PrivateStateStore.applicationSupportDirectory(), defaults: UserDefaults = AppProfile.current.defaults) {
        self.stateDirectory = stateDirectory
        self.secureStore = PrivateStateStore(directory: stateDirectory)
        self.defaults = defaults
        if let savedName = defaults.string(forKey: "device.custom_name")?.trimmingCharacters(in: .whitespacesAndNewlines),
           !savedName.isEmpty {
            self.localName = savedName
        } else {
            self.localName = Host.current().localizedName ?? "Mac"
        }

        if let savedID = defaults.string(forKey: "device.id"), !savedID.isEmpty {
            self.localID = savedID
        } else {
            let id = UUID().uuidString
            defaults.set(id, forKey: "device.id")
            self.localID = id
        }

        if let existingKey = secureStore.data(account: "group-key"), existingKey.count == 32 {
            self.groupKey = existingKey
        } else {
            let generated = Self.generateRandomBytes(count: 32)
            secureStore.set(generated, account: "group-key")
            self.groupKey = generated
        }

        loadPeers()
        loadUnpairedIDs()
        markAllDisconnected()
        defaults.removeObject(forKey: "host-target")
    }

    func setLocalName(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let updated = trimmed.isEmpty ? (Host.current().localizedName ?? "Mac") : trimmed
        self.localName = updated
        if trimmed.isEmpty {
            defaults.removeObject(forKey: "device.custom_name")
        } else {
            defaults.set(trimmed, forKey: "device.custom_name")
        }
        return updated
    }

    func rotateGroupKey() -> Data {
        let fresh = Self.generateRandomBytes(count: 32)
        self.groupKey = fresh
        secureStore.set(fresh, account: "group-key")
        self.peers.removeAll()
        unpairedIDs.removeAll()
        persistPeers()
        persistUnpairedIDs()
        return fresh
    }

    func clearPairingState() {
        forgetAllPeers()
    }

    func addOrUpdatePeer(_ peer: Peer) -> Bool {
        guard peer.id != localID else { return false }
        unpairedIDs.remove(peer.id)
        persistUnpairedIDs()
        peers[peer.id] = peer
        persistPeers()
        return true
    }

    func shouldAcceptPeer(_ peerID: String, isPairingScan: Bool) -> Bool {
        let allowed = PeerPresence.shouldAcceptReturningPeer(
            wasUnpaired: unpairedIDs.contains(peerID),
            isPairingScan: isPairingScan
        )
        if allowed && isPairingScan {
            unpairedIDs.remove(peerID)
            persistUnpairedIDs()
        }
        return allowed
    }

    func forgetPeer(id: String) {
        peers.removeValue(forKey: id)
        unpairedIDs.insert(id)
        persistPeers()
        persistUnpairedIDs()
    }

    func forgetAllPeers() {
        unpairedIDs.formUnion(peers.keys)
        peers.removeAll()
        persistPeers()
        persistUnpairedIDs()
    }

    func setPeerConnected(_ peerID: String, connected: Bool, endpoint: DirectEndpoint? = nil) {
        guard var peer = peers[peerID] else { return }
        peer.connected = connected
        if let endpoint {
            peer.endpoint = endpoint
        }
        peers[peerID] = peer
        persistPeers()
    }

    func markAllDisconnected() {
        for key in peers.keys {
            peers[key]?.connected = false
        }
        persistPeers()
    }

    func renamePeer(id: String, newName: String) -> Bool {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, var peer = peers[id] else { return false }
        peer.name = trimmed
        peers[id] = peer
        persistPeers()
        return true
    }

    func removePeer(id: String) {
        forgetPeer(id: id)
    }

    func peerSnapshot() -> [Peer] {
        peers.values.sorted { $0.name < $1.name }
    }

    private static func generateRandomBytes(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }

    private func loadPeers() {
        guard let data = defaults.data(forKey: "peers"),
              let saved = try? JSONDecoder().decode([Peer].self, from: data) else { return }
        peers = Dictionary(uniqueKeysWithValues: saved.map { ($0.id, $0) })
    }

    private func persistPeers() {
        let stored = Array(peers.values).map { peer in
            Peer(id: peer.id, name: peer.name, endpoint: peer.endpoint, connected: false, platform: peer.platform)
        }
        defaults.set(try? JSONEncoder().encode(stored), forKey: "peers")
    }

    private func loadUnpairedIDs() {
        if let saved = defaults.array(forKey: "unpaired-peer-ids") as? [String] {
            unpairedIDs = Set(saved)
        }
        if let legacy = defaults.array(forKey: "tombstoned-peers") as? [String] {
            unpairedIDs.formUnion(legacy)
            defaults.removeObject(forKey: "tombstoned-peers")
            persistUnpairedIDs()
        }
    }

    private func persistUnpairedIDs() {
        defaults.set(Array(unpairedIDs), forKey: "unpaired-peer-ids")
    }
}
