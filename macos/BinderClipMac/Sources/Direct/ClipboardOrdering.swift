import CoreFoundation
import Foundation

struct ClipboardStamp: Codable, Equatable, Comparable, Sendable {
    enum CodingKeys: String, CodingKey {
        case time = "clipTime", counter = "clipCounter", origin = "clipOrigin", event = "clipEvent", kind = "clipKind", hash = "clipHash"
    }
    static let capability = "clipboardSync"
    let time: Int64
    let counter: Int64
    let origin: String
    let event: String
    let kind: String
    let hash: String
    var fingerprint: String { kind + ":" + hash }
    var fields: [String: Any] {
        ["clipTime": time, "clipCounter": counter, "clipOrigin": origin, "clipEvent": event, "clipKind": kind, "clipHash": hash]
    }
    func validate() throws {
        guard (0...253402300799999).contains(time), (0...1_000_000).contains(counter),
              origin.range(of: "^[A-Za-z0-9._-]{1,128}$", options: .regularExpression) != nil,
              FileTransferProtocol.validID(event), ["text", "image", "empty"].contains(kind),
              hash.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw FileTransferError.invalidMetadata }
    }
    static func parse(_ json: [String: Any]) throws -> ClipboardStamp? {
        let keys = ["clipTime", "clipCounter", "clipOrigin", "clipKind", "clipHash"]
        guard json["clipEvent"] != nil else {
            guard keys.allSatisfy({ json[$0] == nil }) else { throw FileTransferError.invalidMetadata }
            return nil
        }
        func integer(_ key: String) throws -> Int64 {
            guard let value = json[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
                  !["d", "f"].contains(String(cString: value.objCType)) else { throw FileTransferError.invalidMetadata }
            return value.int64Value
        }
        guard let origin = json["clipOrigin"] as? String, let event = json["clipEvent"] as? String,
              let kind = json["clipKind"] as? String, let hash = json["clipHash"] as? String else { throw FileTransferError.invalidMetadata }
        let value = ClipboardStamp(time: try integer("clipTime"), counter: try integer("clipCounter"), origin: origin, event: event, kind: kind, hash: hash)
        try value.validate()
        return value
    }
    static func < (lhs: ClipboardStamp, rhs: ClipboardStamp) -> Bool {
        if lhs.time != rhs.time { return lhs.time < rhs.time }
        if lhs.counter != rhs.counter { return lhs.counter < rhs.counter }
        if lhs.origin != rhs.origin { return lhs.origin < rhs.origin }
        return lhs.event < rhs.event
    }
}

struct ClipboardCheckpoint: Codable {
    let head: ClipboardStamp
}

/// The Mac supplies the shared clock domain. No clipboard bytes are written to the checkpoint.
final class ClipboardOrdering {
    private let localID: String
    private let wall: () -> Int64
    private(set) var head: ClipboardStamp?
    private var seen: [String] = []
    init(localID: String, checkpoint: ClipboardCheckpoint? = nil, wall: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.localID = localID; self.wall = wall
        if let stamp = checkpoint?.head, (try? stamp.validate()) != nil { head = stamp; remember(stamp.event) }
    }
    func observe(kind: String, hash: String, force: Bool = false, bootstrap: Bool = false, origin: String? = nil) -> ClipboardStamp? {
        guard force || head?.fingerprint != kind + ":" + hash else { return nil }
        if bootstrap && kind == "empty" { return nil }
        let time = bootstrap ? head?.time ?? 0 : max(wall(), head?.time ?? 0)
        let count = time == head?.time ? (head?.counter ?? 0) + 1 : 0
        let stamp = ClipboardStamp(time: count > 1_000_000 ? time + 1 : time, counter: count > 1_000_000 ? 0 : count,
                                   origin: origin ?? localID, event: UUID().uuidString.lowercased(), kind: kind, hash: hash)
        head = stamp
        remember(stamp.event)
        return stamp
    }
    @discardableResult func receive(_ stamp: ClipboardStamp) -> Bool {
        guard !seen.contains(stamp.event), head?.event != stamp.event, head == nil || stamp > head! else { return false }
        let changed = head?.fingerprint != stamp.fingerprint
        head = stamp; remember(stamp.event)
        return changed
    }
    func reset() { head = nil; seen.removeAll() }
    var checkpoint: ClipboardCheckpoint? { head.map { ClipboardCheckpoint(head: $0) } }
    private func remember(_ event: String) {
        seen.append(event)
        if seen.count > 128 { seen.removeFirst(seen.count - 128) }
    }
}
