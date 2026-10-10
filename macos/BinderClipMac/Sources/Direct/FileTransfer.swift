import CryptoKit
import CoreFoundation
import Darwin
import Foundation

struct TransferMetadata: Codable, Equatable, Sendable {
    let id: String
    let kind: String
    let name: String
    let mimeType: String
    let size: Int64
    let sha256: String
    var clipboard: ClipboardStamp? = nil

    func validate() throws {
        try clipboard?.validate()
        let maximum = kind == "image" ? Int64(ImagePayload.maximumBytes) : FileTransferProtocol.maximumFileBytes
        guard FileTransferProtocol.validID(id), kind == "file" || kind == "image",
              name == FileTransferProtocol.safeName(name), mimeType.utf8.count <= 127,
              mimeType.range(of: "^[a-zA-Z0-9][a-zA-Z0-9.+-]*/[a-zA-Z0-9][a-zA-Z0-9.+-]*$", options: .regularExpression) != nil,
              (0...maximum).contains(size),
              kind != "image" || (size > 0 && ImagePayload.allowedMIMETypes.contains(mimeType)),
              sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              clipboard == nil || (kind == "image" && clipboard?.kind == "image" && clipboard?.hash == sha256) else {
            throw FileTransferError.invalidMetadata
        }
    }
    var json: [String: Any] {
        var value: [String: Any] = ["type": "transfer_offer", "id": id, "kind": kind, "name": name, "mimeType": mimeType, "size": size, "sha256": sha256]
        if let clipboard { value["clipboard"] = clipboard.fields }
        return value
    }
    static func parse(_ json: [String: Any]) throws -> Self {
        if let raw = json["clipboard"] {
            guard let fields = raw as? [String: Any], try ClipboardStamp.parse(fields) != nil else { throw FileTransferError.invalidMetadata }
        }
        guard let number = json["size"] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(), String(cString: number.objCType) != "d" else {
            throw FileTransferError.invalidMetadata
        }
        let result = try JSONDecoder().decode(Self.self, from: JSONSerialization.data(withJSONObject: json))
        try result.validate()
        return result
    }
}

enum FileTransferError: Error { case invalidMetadata, invalidChunk, queueFull, integrity, storage }

enum FileTransferProtocol {
    static let capability = "chunkedTransfers"
    static let version = 1
    static let chunkBytes = 512 * 1024
    static let maximumFileBytes: Int64 = 100 * 1024 * 1024
    static let headerBytes = 48
    private static let magic = Data([0x42, 0x43, 0x54, 0x31])

    static func validID(_ id: String) -> Bool { UUID(uuidString: id)?.uuidString.lowercased() == id }
    static func safeName(_ value: String, maximumBytes: Int = 180) -> String {
        let base = value.replacingOccurrences(of: "\\", with: "/").components(separatedBy: "/").last ?? ""
        let forbidden = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: ":<>\"|?*"))
        let clean = String(String.UnicodeScalarView(base.unicodeScalars.filter { !forbidden.contains($0) }))
            .trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "."))
        var result = ""
        for scalar in clean.unicodeScalars {
            let next = String(scalar)
            if result.utf8.count + next.utf8.count > maximumBytes { break }
            result += next
        }
        result = result.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return result.isEmpty ? "file" : result
    }
    static func isChunk(_ data: Data) -> Bool { data.prefix(4) == magic }
    static func packChunk(id: String, offset: Int64, data: Data) throws -> Data {
        guard validID(id), offset >= 0, (1...chunkBytes).contains(data.count) else { throw FileTransferError.invalidChunk }
        var result = magic + Data(id.utf8)
        var bigEndian = UInt64(offset).bigEndian
        withUnsafeBytes(of: &bigEndian) { result.append(contentsOf: $0) }
        result.append(data)
        return result
    }
    static func unpackChunk(_ packet: Data) throws -> (String, Int64, Data) {
        guard isChunk(packet), ((headerBytes + 1)...(headerBytes + chunkBytes)).contains(packet.count) else { throw FileTransferError.invalidChunk }
        let id = String(decoding: packet.dropFirst(4).prefix(36), as: UTF8.self)
        let offset = packet.dropFirst(40).prefix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        guard validID(id), offset <= UInt64(Int64.max) else { throw FileTransferError.invalidChunk }
        return (id, Int64(offset), Data(packet.dropFirst(headerBytes)))
    }
    static func hash(_ file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var digest = SHA256()
        while let data = try handle.read(upToCount: 64 * 1024), !data.isEmpty { digest.update(data: data) }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

struct OutgoingTransfer: Sendable { let peerID: String; let metadata: TransferMetadata; let file: URL }
struct ReceivedFile: Sendable { let metadata: TransferMetadata; let file: URL }
struct TransferReceipt { let offset: Int64; let completed: Bool }

struct TransferProgress: Sendable {
    enum Direction: String, Sendable { case sending, receiving }
    enum Phase: Sendable { case preparing, sending, receiving, verifying, sent, received, declined, failed, invalid, paused }
    let peerID: String
    let metadata: TransferMetadata
    let direction: Direction
    let phase: Phase
    let completedBytes: Int64
    var identity: String { peerID + "/" + direction.rawValue + "/" + metadata.id }
    var isActive: Bool { [.preparing, .sending, .receiving, .verifying].contains(phase) }
    var fraction: Double {
        if phase == .sent || phase == .received || phase == .verifying { return 1 }
        guard metadata.size > 0 else { return 0 }
        return min(1, max(0, Double(completedBytes) / Double(metadata.size)))
    }
    var message: String {
        let kind = L10n.tr(metadata.kind == "image" ? "transfer_kind_image" : "transfer_kind_file")
        switch phase {
        case .preparing: return L10n.tr("transfer_offering", kind)
        case .sending: return L10n.tr("transfer_sending", kind, Int(fraction * 100))
        case .receiving: return L10n.tr("transfer_receiving", kind) + " (\(Int(fraction * 100))%)"
        case .verifying: return L10n.tr("transfer_verifying", kind)
        case .sent: return L10n.tr("transfer_sent", kind)
        case .received: return L10n.tr("transfer_received", kind)
        case .declined: return L10n.tr("transfer_declined")
        case .failed: return L10n.tr("transfer_failed")
        case .invalid: return L10n.tr("transfer_invalid")
        case .paused: return L10n.tr("transfer_paused")
        }
    }
    var icon: String {
        switch phase {
        case .sent, .received: return "checkmark.circle.fill"
        case .declined, .failed, .invalid: return "exclamationmark.circle.fill"
        case .paused: return "pause.circle.fill"
        case .verifying: return "checkmark.shield.fill"
        default: return direction == .sending ? "arrow.up.doc.fill" : "arrow.down.doc.fill"
        }
    }
}

final class FileTransferStore: @unchecked Sendable {
    let root: URL
    let receivedDirectory: URL
    private let manager = FileManager.default
    private var outgoing: URL { root.appendingPathComponent("outgoing", isDirectory: true) }
    private var incoming: URL { root.appendingPathComponent("incoming", isDirectory: true) }
    private let maximumAge: TimeInterval = 24 * 60 * 60

    init(root: URL, receivedDirectory: URL) {
        self.root = root
        self.receivedDirectory = receivedDirectory
    }

    func stage(peerID: String, name: String, mimeType: String, kind: String, input: FileHandle, clipboard: ClipboardStamp? = nil) throws -> OutgoingTransfer {
        cleanup()
        if let clipboard {
            for directory in children(outgoing) {
                guard let json = try? readJSON(directory.appendingPathComponent("metadata")), json["peerId"] as? String == peerID,
                      let meta = try? TransferMetadata.parse(json), meta.clipboard?.event == clipboard.event,
                      fileSize(directory.appendingPathComponent("body")) == meta.size else { continue }
                return OutgoingTransfer(peerID: peerID, metadata: meta, file: directory.appendingPathComponent("body"))
            }
        }
        guard !peerID.isEmpty, children(outgoing).count < 8 else { throw FileTransferError.queueFull }
        let id = UUID().uuidString.lowercased()
        let directory = outgoing.appendingPathComponent(id, isDirectory: true)
        try makeDirectory(directory)
        let body = directory.appendingPathComponent("body")
        do {
            guard manager.createFile(atPath: body.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw FileTransferError.storage }
            let output = try FileHandle(forWritingTo: body)
            defer { try? output.close() }
            let maximum = kind == "image" ? Int64(ImagePayload.maximumBytes) : FileTransferProtocol.maximumFileBytes
            var size: Int64 = 0
            var hash = SHA256()
            while let data = try input.read(upToCount: 64 * 1024), !data.isEmpty {
                size += Int64(data.count)
                guard size <= maximum else { throw FileTransferError.invalidMetadata }
                hash.update(data: data)
                try output.write(contentsOf: data)
            }
            try output.synchronize()
            let meta = TransferMetadata(id: id, kind: kind, name: FileTransferProtocol.safeName(name), mimeType: mimeType,
                size: size, sha256: hash.finalize().map { String(format: "%02x", $0) }.joined(), clipboard: clipboard)
            try meta.validate()
            var json = meta.json
            json["peerId"] = peerID
            try writeJSON(json, to: directory.appendingPathComponent("metadata"))
            return OutgoingTransfer(peerID: peerID, metadata: meta, file: body)
        } catch { try? manager.removeItem(at: directory); throw error }
    }
    func stageImage(peerID: String, image: ImagePayload, clipboard: ClipboardStamp? = nil) throws -> OutgoingTransfer {
        try makeDirectory(root)
        let temporary = root.appendingPathComponent("image-\(UUID().uuidString)")
        defer { try? manager.removeItem(at: temporary) }
        try image.data.write(to: temporary, options: .atomic)
        let input = try FileHandle(forReadingFrom: temporary)
        defer { try? input.close() }
        return try stage(peerID: peerID, name: "image", mimeType: image.mimeType, kind: "image", input: input, clipboard: clipboard)
    }
    func next(peerID: String) -> OutgoingTransfer? {
        cleanup()
        return children(outgoing).sorted { modification($0) < modification($1) }.compactMap { directory -> OutgoingTransfer? in
            guard let json = try? readJSON(directory.appendingPathComponent("metadata")), json["peerId"] as? String == peerID,
                  let meta = try? TransferMetadata.parse(json), fileSize(directory.appendingPathComponent("body")) == meta.size else { return nil }
            return OutgoingTransfer(peerID: peerID, metadata: meta, file: directory.appendingPathComponent("body"))
        }.first
    }
    func removeOutgoing(_ id: String) { if FileTransferProtocol.validID(id) { try? manager.removeItem(at: outgoing.appendingPathComponent(id)) } }
    func accept(peerID: String, meta: TransferMetadata) throws -> TransferReceipt {
        try meta.validate()
        cleanup()
        let directory = try incomingDirectory(peerID: peerID, id: meta.id)
        let manifest = directory.appendingPathComponent("metadata")
        if manager.fileExists(atPath: manifest.path) {
            let saved = try readJSON(manifest)
            guard try TransferMetadata.parse(saved) == meta else { throw FileTransferError.invalidMetadata }
            if saved["completed"] as? Bool == true { return TransferReceipt(offset: meta.size, completed: true) }
            if meta.kind == "file", let name = saved["destination"] as? String,
               name == FileTransferProtocol.safeName(name, maximumBytes: 240), saved["committing"] as? Bool == true {
                let destination = receivedDirectory.appendingPathComponent(name)
                let body = directory.appendingPathComponent("body")
                if !manager.fileExists(atPath: body.path), fileSize(destination) == meta.size,
                   try FileTransferProtocol.hash(destination) == meta.sha256 {
                    var receipt = meta.json; receipt["completed"] = true
                    try writeJSON(receipt, to: manifest)
                    return TransferReceipt(offset: meta.size, completed: true)
                }
            }
        } else {
            guard children(incoming).filter({ (try? readJSON($0.appendingPathComponent("metadata"))["completed"] as? Bool) != true }).count < 4 else { throw FileTransferError.queueFull }
            try makeDirectory(directory)
            try writeJSON(meta.json, to: manifest)
        }
        let body = directory.appendingPathComponent("body")
        if !manager.fileExists(atPath: body.path) {
            guard manager.createFile(atPath: body.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw FileTransferError.storage }
        }
        let length = fileSize(body)
        guard length <= meta.size else { throw FileTransferError.invalidChunk }
        let durable = length == meta.size ? length : length / Int64(FileTransferProtocol.chunkBytes) * Int64(FileTransferProtocol.chunkBytes)
        if durable != length {
            let handle = try FileHandle(forWritingTo: body)
            defer { try? handle.close() }
            try handle.truncate(atOffset: UInt64(durable))
        }
        return TransferReceipt(offset: durable, completed: false)
    }
    func append(peerID: String, meta: TransferMetadata, offset: Int64, bytes: Data) throws -> Int64 {
        let directory = try incomingDirectory(peerID: peerID, id: meta.id)
        let body = directory.appendingPathComponent("body")
        guard fileSize(body) == offset, offset < meta.size,
              Int64(bytes.count) == min(Int64(FileTransferProtocol.chunkBytes), meta.size - offset) else { throw FileTransferError.invalidChunk }
        let handle = try FileHandle(forWritingTo: body)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: bytes)
        try handle.synchronize()
        try manager.setAttributes([.modificationDate: Date()], ofItemAtPath: directory.path)
        return offset + Int64(bytes.count)
    }
    func complete(peerID: String, meta: TransferMetadata) throws -> ReceivedFile {
        let directory = try incomingDirectory(peerID: peerID, id: meta.id)
        let body = directory.appendingPathComponent("body")
        guard fileSize(body) == meta.size, try FileTransferProtocol.hash(body) == meta.sha256 else {
            try? manager.removeItem(at: directory)
            throw FileTransferError.integrity
        }
        var destination = body
        if meta.kind == "file" {
            try makeDirectory(receivedDirectory)
            destination = uniqueDestination(meta.name)
            var intent = meta.json
            intent["destination"] = destination.lastPathComponent
            intent["committing"] = true
            try writeJSON(intent, to: directory.appendingPathComponent("metadata"))
            try manager.moveItem(at: body, to: destination)
            // Files are revealed in Finder, never launched by the receiver.
            let quarantine = Array("0081;\(String(Int(Date().timeIntervalSince1970), radix: 16));BinderClip;".utf8)
            quarantine.withUnsafeBytes { _ = setxattr(destination.path, "com.apple.quarantine", $0.baseAddress, $0.count, 0, 0) }
        }
        var receipt = meta.json
        receipt["completed"] = true
        try writeJSON(receipt, to: directory.appendingPathComponent("metadata"))
        return ReceivedFile(metadata: meta, file: destination)
    }
    func discardIncoming(peerID: String, id: String) {
        if let directory = try? incomingDirectory(peerID: peerID, id: id) { try? manager.removeItem(at: directory) }
    }
    func clearPairing(peerID: String? = nil) {
        if let peerID {
            for directory in children(outgoing) where (try? readJSON(directory.appendingPathComponent("metadata"))["peerId"] as? String) == peerID { try? manager.removeItem(at: directory) }
            let prefix = SyncProtocol.sha256Hex(peerID) + "-"
            for directory in children(incoming) where directory.lastPathComponent.hasPrefix(prefix) { try? manager.removeItem(at: directory) }
        } else { try? manager.removeItem(at: outgoing); try? manager.removeItem(at: incoming) }
    }
    private func incomingDirectory(peerID: String, id: String) throws -> URL {
        guard !peerID.isEmpty, FileTransferProtocol.validID(id) else { throw FileTransferError.invalidMetadata }
        return incoming.appendingPathComponent(SyncProtocol.sha256Hex(peerID) + "-" + id, isDirectory: true)
    }
    private func uniqueDestination(_ name: String) -> URL {
        let original = receivedDirectory.appendingPathComponent(name)
        if !manager.fileExists(atPath: original.path) { return original }
        let extensionPart = original.pathExtension
        let stem = original.deletingPathExtension().lastPathComponent
        var number = 2
        while true {
            let candidate = receivedDirectory.appendingPathComponent("\(stem) (\(number))" + (extensionPart.isEmpty ? "" : ".\(extensionPart)"))
            if !manager.fileExists(atPath: candidate.path) { return candidate }
            number += 1
        }
    }
    private func cleanup() {
        for base in [outgoing, incoming] {
            for directory in children(base) where modification(directory) < Date().addingTimeInterval(-maximumAge) { try? manager.removeItem(at: directory) }
        }
    }
    private func children(_ url: URL) -> [URL] { (try? manager.contentsOfDirectory(at: url, includingPropertiesForKeys: nil)) ?? [] }
    private func modification(_ url: URL) -> Date { (try? manager.attributesOfItem(atPath: url.path)[.modificationDate] as? Date) ?? .distantPast }
    private func fileSize(_ url: URL) -> Int64 { (try? manager.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? -1 }
    private func makeDirectory(_ url: URL) throws { try manager.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]) }
    private func readJSON(_ url: URL) throws -> [String: Any] {
        guard (1...8192).contains(fileSize(url)), let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] else { throw FileTransferError.invalidMetadata }
        return json
    }
    private func writeJSON(_ json: [String: Any], to url: URL) throws {
        try JSONSerialization.data(withJSONObject: json).write(to: url, options: .atomic)
        try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}
