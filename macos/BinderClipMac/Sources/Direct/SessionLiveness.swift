import Foundation

enum SessionLiveness {
    static let heartbeatBudget: TimeInterval = 5
    static let heartbeatSleepBudget: TimeInterval = 60
    static let idleHeartbeatInterval: TimeInterval = 15
    static func nextPingAfterPowerChange(sleeping: Bool, now: Date) -> Date {
        sleeping ? now.addingTimeInterval(idleHeartbeatInterval) : now
    }
    static func now() -> Date { Date(timeIntervalSince1970: ProcessInfo.processInfo.systemUptime) }

    static func ipv4Bytes(_ ip: String) -> [UInt8]? {
        let parts = ip.split(separator: ".")
        guard parts.count == 4 else { return nil }
        let bytes = parts.compactMap { UInt8($0) }
        guard bytes.count == 4 else { return nil }
        return bytes
    }

    static func matchingPrefixBits(remote: String, local: String) -> Int {
        guard let remoteBytes = ipv4Bytes(remote), let localBytes = ipv4Bytes(local) else { return -1 }
        var bits = 0
        for index in 0..<4 {
            if remoteBytes[index] == localBytes[index] {
                bits += 8
                continue
            }
            let xor = remoteBytes[index] ^ localBytes[index]
            for shift in stride(from: 7, through: 0, by: -1) {
                if xor & (1 << shift) != 0 { return bits }
                bits += 1
            }
        }
        return bits
    }

    /// Longest IPv4 prefix match. Mesh remotes (`100.`) prefer a local `100.` address.
    static func boundLocalAddress(remote: String, localAddresses: [String]) -> String? {
        let locals = localAddresses.filter { ipv4Bytes($0) != nil }
        guard !locals.isEmpty else { return nil }
        if remote.hasPrefix("100."), let mesh = locals.first(where: { $0.hasPrefix("100.") }) {
            return mesh
        }
        return locals.max { lhs, rhs in
            matchingPrefixBits(remote: remote, local: lhs) < matchingPrefixBits(remote: remote, local: rhs)
        }
    }

    static func shouldEvict(boundLocal: String?, currentLocals: [String]) -> Bool {
        guard let boundLocal, ipv4Bytes(boundLocal) != nil else { return false }
        return !currentLocals.contains(boundLocal)
    }

    static func isAlive(
        boundLocal: String?,
        currentLocals: [String],
        lastHeard: Date?,
        now: Date,
        budget: TimeInterval = heartbeatBudget
    ) -> Bool {
        guard let lastHeard else { return false }
        if shouldEvict(boundLocal: boundLocal, currentLocals: currentLocals) { return false }
        return now.timeIntervalSince(lastHeard) <= budget
    }
}
