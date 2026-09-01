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

public struct ImageMetadata: Codable, Sendable, Equatable {
    public let type: String
    public let id: String
    public let mimeType: String
    public let hash: String
    public let originId: String
    public let size: Int

    public init(id: String, mimeType: String, hash: String, originId: String, size: Int) {
        self.type = "image"
        self.id = id
        self.mimeType = mimeType
        self.hash = hash
        self.originId = originId
        self.size = size
    }
}

public enum SyncProtocol {
    public static let version = 2
    public static let btServiceUuid = "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a73"
    public static let btPsmCharUuid = "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a74"
    public static let btWriteCharUuid = "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a75"
    public static let btNotifyCharUuid = "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a76"
    public static let defaultPort: UInt16 = 39_421
    public static let maximumTextBytes = 1_048_576
    public static let maximumImageBytes = 32 * 1024 * 1024
    public static let maximumAdvertisedEndpoints = 4
    public static let heartbeatInterval: TimeInterval = 2
    public static let heartbeatBudget: TimeInterval = SessionLiveness.heartbeatBudget
    public static let heartbeatSleepBudget: TimeInterval = SessionLiveness.heartbeatSleepBudget
    public static let authDeadline: TimeInterval = 2
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

    public static func createPairingURL(deviceId: String, deviceName: String, psk: String, endpoints: [String]) -> URL? {
        var components = URLComponents()
        components.scheme = "binderclip"
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

    public static func parsePairingURL(_ string: String) -> PairingInfo? {
        guard let components = URLComponents(string: string),
              components.scheme == "binderclip",
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

    public static func packImage(image: ImagePayload, originId: String) -> Data {
        let meta = ImageMetadata(
            id: image.id.uuidString,
            mimeType: image.mimeType,
            hash: image.sha256,
            originId: originId,
            size: image.data.count
        )
        guard let metaJson = try? JSONEncoder().encode(meta) else {
            return Data()
        }
        var length = UInt32(metaJson.count).bigEndian
        var packet = Data()
        packet.append(Data(bytes: &length, count: 4))
        packet.append(metaJson)
        packet.append(image.data)
        return packet
    }

    public static func unpackImage(_ packet: Data) -> (metadata: ImageMetadata, imageData: Data)? {
        guard packet.count >= 4 else { return nil }
        let headerLen = packet.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
        let metaStart = 4
        let metaEnd = metaStart + Int(headerLen)
        guard packet.count >= metaEnd else { return nil }

        let metaData = packet.subdata(in: metaStart..<metaEnd)
        guard let meta = try? JSONDecoder().decode(ImageMetadata.self, from: metaData) else {
            return nil
        }
        let imageData = packet.subdata(in: metaEnd..<packet.count)
        guard imageData.count == meta.size else { return nil }
        return (meta, imageData)
    }
}
