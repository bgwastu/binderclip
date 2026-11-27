import CryptoKit
import Foundation

/// Used only on the server queue. A share belongs to the peer selected when it was queued.
final class SharedTextOutbox {
    struct Share: Codable {
        let peerID: String
        let id: String
        let text: String
    }

    private let file: URL
    private var shares: [Share]

    init(directory: URL, key: Data, peers: Set<String>) {
        file = directory.appendingPathComponent("shared-text-outbox")
        shares = ((try? Self.load(file, key: key)) ?? []).filter { peers.contains($0.peerID) }
    }

    var peers: Set<String> { Set(shares.map(\.peerID)) }
    func next(for peerID: String) -> Share? { shares.first { $0.peerID == peerID } }

    @discardableResult
    func enqueue(_ text: String, for peers: [String], key: Data) throws -> Bool {
        let pending = Dictionary(grouping: shares, by: \.peerID)
        let recipients = peers.filter { (pending[$0]?.count ?? 0) < 8 }.prefix(32 - shares.count)
        guard !recipients.isEmpty else { return false }
        let id = UUID().uuidString.lowercased()
        try save(shares + recipients.map { Share(peerID: $0, id: id, text: text) }, key: key)
        return recipients.count == peers.count
    }

    func acknowledge(_ id: String, from peerID: String, key: Data) throws -> Bool {
        guard let index = shares.firstIndex(where: { $0.peerID == peerID }), shares[index].id == id else { return false }
        var remaining = shares
        remaining.remove(at: index)
        try save(remaining, key: key)
        return true
    }

    func clear(peerID: String?, key: Data) throws {
        let remaining = shares.filter { peerID != nil && $0.peerID != peerID }
        guard remaining.count != shares.count else { return }
        // Drop revoked entries from memory even if the disk is currently unwritable.
        shares = remaining
        try save(remaining, key: key)
    }

    private func save(_ updated: [Share], key: Data) throws {
        let sealed = try AES.GCM.seal(JSONEncoder().encode(updated), using: Self.storageKey(key))
        guard let bytes = sealed.combined else { throw FileTransferError.storage }
        try bytes.write(to: file, options: .atomic)
        shares = updated
    }

    private static func load(_ file: URL, key: Data) throws -> [Share] {
        let sealed = try AES.GCM.SealedBox(combined: Data(contentsOf: file))
        return try JSONDecoder().decode([Share].self, from: AES.GCM.open(sealed, using: storageKey(key)))
    }

    private static func storageKey(_ key: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: key),
                               info: Data("BinderClip shared text outbox".utf8), outputByteCount: 32)
    }
}
