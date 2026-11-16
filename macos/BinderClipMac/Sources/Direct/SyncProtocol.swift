import CryptoKit
import Foundation

public struct PairingInfo: Sendable, Equatable {
    public let version: Int
    public let deviceId: String
    public let deviceName: String
    public let psk: String
    public let endpoints: [String]

    public init(version: Int = SyncProtocol.version, deviceId: String, deviceName: String, psk: String, endpoints: [String]) {
        self.version = version
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.psk = psk
        self.endpoints = endpoints
    }
}

public enum SyncProtocol {
    public static let version = 3
    public static let btServiceUuid = AppProfile.current.bluetoothUUID(1)
    public static let btPsmCharUuid = AppProfile.current.bluetoothUUID(2)
    public static let btWriteCharUuid = AppProfile.current.bluetoothUUID(3)
    public static let btNotifyCharUuid = AppProfile.current.bluetoothUUID(4)
    public static let defaultPort: UInt16 = AppProfile.current.port
    public static let maximumTextBytes = 1_048_576
    /// How long a pairing QR stays valid; Bluetooth pairing advertising runs for the same window.
    public static let pairingWindowSeconds: TimeInterval = 300
    public static let maximumImageBytes = 32 * 1024 * 1024
    public static let maximumAdvertisedEndpoints = 4
    public static let heartbeatInterval: TimeInterval = 2
    public static let heartbeatBudget: TimeInterval = SessionLiveness.heartbeatBudget
    public static let heartbeatSleepBudget: TimeInterval = SessionLiveness.heartbeatSleepBudget
    public static let authDeadline: TimeInterval = 5
    public static let listenerBackoffCap: TimeInterval = 16

    public static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    public static func sha256Hex(_ string: String) -> String {
        sha256Hex(Data(string.utf8))
    }

    public static func urlSafeBase64(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decodeBase64(_ string: String) -> Data? {
        var normalized = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder != 0 {
            normalized.append(String(repeating: "=", count: 4 - remainder))
        }
        return Data(base64Encoded: normalized)
    }

    public static func parseEndpoint(_ raw: String) -> (host: String, port: UInt16)? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.hasPrefix("["), let close = trimmed.firstIndex(of: "]") {
            let host = String(trimmed[trimmed.index(after: trimmed.startIndex)..<close])
            let rest = trimmed[trimmed.index(after: close)...]
            guard rest.hasPrefix(":"), let port = UInt16(rest.dropFirst()) else { return nil }
            return (host, port)
        }
        guard let colon = trimmed.lastIndex(of: ":") else {
            return (trimmed, defaultPort)
        }
        let host = String(trimmed[..<colon])
        let portPart = String(trimmed[trimmed.index(after: colon)...])
        guard !host.isEmpty, let port = UInt16(portPart) else { return nil }
        return (host, port)
    }

    public static func advertisedEndpoints(from addresses: [String], port: UInt16 = defaultPort) -> [String] {
        let unique = NSOrderedSet(array: addresses.filter { !$0.isEmpty && $0 != "127.0.0.1" && !$0.hasPrefix("169.254.") }).compactMap { $0 as? String }
        let sorted = unique.sorted { lhs, rhs in
            endpointRank(lhs) < endpointRank(rhs)
        }
        return Array(sorted.prefix(maximumAdvertisedEndpoints)).map { "\($0):\(port)" }
    }

    private static func endpointRank(_ ip: String) -> Int {
        // Mesh (Tailscale/WARP 100.x) is ranked first: when the phone is on the same tunnel the
        // mesh path is stable, whereas a WARP/Tailscale VPN often hijacks the LAN subnet (captured
        // routes) so "LAN" traffic actually tunnels and flaps. Only fall back to LAN when no mesh
        // endpoint exists.
        if ip.hasPrefix("100.") { return 0 }
        if ip.hasPrefix("192.168.") || ip.hasPrefix("10.") || ip.hasPrefix("172.") { return 1 }
        return 2
    }

    /// Links a phone may open in the Mac's browser: http(s) with a host and nothing else.
    public static func isBrowsableURL(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return false }
        return !(url.host ?? "").isEmpty
    }

    public static func createPairingURL(deviceId: String, deviceName: String, psk: String, endpoints: [String], profile: AppProfile = .current) -> URL? {
        var components = URLComponents()
        components.scheme = profile.pairingScheme
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "v", value: String(version)),
            URLQueryItem(name: "id", value: deviceId),
            URLQueryItem(name: "name", value: deviceName),
            URLQueryItem(name: "psk", value: psk),
            URLQueryItem(name: "endpoints", value: endpoints.joined(separator: ","))
        ]
        return components.url
    }

    public static func parsePairingURL(_ string: String, profile: AppProfile = .current) -> PairingInfo? {
        guard let components = URLComponents(string: string),
              components.scheme == profile.pairingScheme,
              components.host == "pair" else {
            return nil
        }
        let items = components.queryItems ?? []
        let getParam = { (name: String) -> String? in
            items.first(where: { $0.name == name })?.value
        }
        guard let id = getParam("id"), !id.isEmpty,
              let psk = getParam("psk"), !psk.isEmpty else {
            return nil
        }
        let v = getParam("v").flatMap(Int.init) ?? version
        let name = getParam("name") ?? "Mac"
        let rawEndpoints = getParam("endpoints") ?? ""
        let endpoints = rawEndpoints
            .split(separator: ",")
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        return PairingInfo(version: v, deviceId: id, deviceName: name, psk: psk, endpoints: endpoints)
    }
}
