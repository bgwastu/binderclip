import AppKit

/// Production identifiers remain compatible; debug owns an independent protocol realm.
public struct AppProfile: Equatable, Sendable {
    public let isDebug: Bool
    public static let production = AppProfile(isDebug: false)
    public static let debug = AppProfile(isDebug: true)
    public static let current: AppProfile = {
        let environment = Bundle.main.object(forInfoDictionaryKey: "BinderClipEnvironment") as? String
        let id = Bundle.main.bundleIdentifier
        // Older debug bundles also stay isolated. A production build cannot select debug via a URL.
        return environment == "debug" || id == debug.bundleID ? .debug : .production
    }()
    public var bundleID: String { isDebug ? "net.wastu.binderclip.debug.mb" : "net.wastu.binderclip" }
    public var displayName: String { isDebug ? "BinderClip Debug" : "BinderClip" }
    public var port: UInt16 { isDebug ? 39422 : 39421 }
    public var pairingScheme: String { isDebug ? "binderclip-debug" : "binderclip" }
    public var bonjourType: String { isDebug ? "_binderclip-dbg._tcp" : "_binderclip._tcp" }
    public var cryptoDomain: String { isDebug ? "BinderClip/debug/v3" : "BinderClip/v3" }
    public var bluetoothEnabledByDefault: Bool { !isDebug }
    // Legacy debug defaults contained production-group peers. Never import that roster.
    public var defaultsSuiteName: String { isDebug ? bundleID + ".isolated" : bundleID }
    public var defaults: UserDefaults { isDebug ? UserDefaults(suiteName: defaultsSuiteName)! : .standard }
    public var stateDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(bundleID, isDirectory: true)
    }
    public var pasteboard: NSPasteboard {
        isDebug ? NSPasteboard(name: .init(bundleID + ".clipboard")) : .general
    }
    public func bluetoothUUID(_ index: Int) -> String {
        precondition((1...4).contains(index))
        return isDebug ? "a2b76ec4-81f0-4dbe-b5d1-770b3e28ad0\(index)"
            : "7d3e0f5a-9b1c-4e8d-a6f2-0c4b8d1e5a7\(index + 2)"
    }
}
