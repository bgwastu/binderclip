import AppKit

/// What a device's "Send browser tab" menu item carries: the URL it displays and sends.
struct BrowserTabTarget {
    let url: URL
    let peerId: String?
}

/// Finds the front tab of the frontmost supported browser.
///
/// `NSAppleScript` answers only as fast as the browser does, and not at all while the Automation
/// prompt is up. The status menu must open instantly, so every read runs on a private queue and
/// lands in `cached`; the menu renders from the cache and refreshes it just after opening.
final class BrowserTabReader {
    struct Tab {
        let browser: String
        let url: URL
    }

    struct Browser: Equatable {
        let bundleId: String
        let name: String
        let isChromium: Bool
    }

    static let browsers: [Browser] = [
        Browser(bundleId: "com.brave.Browser", name: "Brave", isChromium: true),
        Browser(bundleId: "com.google.Chrome", name: "Google Chrome", isChromium: true),
        Browser(bundleId: "com.google.Chrome.canary", name: "Google Chrome Canary", isChromium: true),
        Browser(bundleId: "company.thebrowser.Browser", name: "Arc", isChromium: true),
        Browser(bundleId: "com.microsoft.edgemac", name: "Microsoft Edge", isChromium: true),
        Browser(bundleId: "com.vivaldi.Vivaldi", name: "Vivaldi", isChromium: true),
        Browser(bundleId: "com.operasoftware.Opera", name: "Opera", isChromium: true),
        Browser(bundleId: "com.kagi.kagisafari", name: "Orion", isChromium: true),
        Browser(bundleId: "com.apple.Safari", name: "Safari", isChromium: false),
        Browser(bundleId: "com.apple.SafariTechnologyPreview", name: "Safari Technology Preview", isChromium: false),
    ]

    /// Called on the main queue with the Automation permission state the read discovered.
    var onAutomationPermission: ((Bool) -> Void)?
    /// Called on the main queue after each read, with nil when no browser has a page open.
    var onRead: ((Tab?) -> Void)?

    private(set) var cached: Tab?

    private let queue = DispatchQueue(label: "net.wastu.binderclip.browser-tab", qos: .utility)
    private var readInFlight = false
    private var readQueued = false
    private var lastReadAt = Date.distantPast

    static func isSupported(bundleId: String) -> Bool {
        browsers.contains { $0.bundleId == bundleId }
    }

    /// Running browsers worth asking, frontmost first. A read only queries the first few so a single
    /// menu open cannot wait on every browser installed.
    static func candidates(running: Set<String>, frontmost: String?, limit: Int = 2) -> [Browser] {
        let runningBrowsers = browsers.filter { running.contains($0.bundleId) }
        guard let frontmost, runningBrowsers.contains(where: { $0.bundleId == frontmost }) else {
            return Array(runningBrowsers.prefix(limit))
        }
        let front = runningBrowsers.filter { $0.bundleId == frontmost }
        return Array((front + runningBrowsers.filter { $0.bundleId != frontmost }).prefix(limit))
    }

    static func script(for browser: Browser) -> String {
        if browser.isChromium {
            return """
            tell application id "\(browser.bundleId)"
                try
                    set u to URL of active tab of front window
                    if u is not "" then return u
                end try
            end tell
            """
        }
        return """
        tell application id "\(browser.bundleId)"
            try
                set u to URL of front document
                if u is not "" then return u
            end try
        end tell
        """
    }

    /// Only real web pages leave the Mac: a browser tab that answers with something else is ignored.
    static func url(fromAppleScriptResult text: String?) -> URL? {
        guard let raw = text?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        guard let url = URL(string: raw), let scheme = url.scheme?.lowercased() else { return nil }
        guard scheme == "http" || scheme == "https" else { return nil }
        return url
    }

    /// Never blocks the caller. Call on the main queue.
    ///
    /// - Parameter minimumInterval: skips the read when one already ran this recently, so switching
    ///   between apps does not run AppleScript every time.
    func refresh(minimumInterval: TimeInterval = 0) {
        if minimumInterval > 0, Date().timeIntervalSince(lastReadAt) < minimumInterval { return }
        if readInFlight {
            readQueued = true
            return
        }
        readInFlight = true
        queue.async { [weak self] in
            let read = Self.read()
            DispatchQueue.main.async {
                guard let self else { return }
                self.readInFlight = false
                self.lastReadAt = Date()
                self.cached = read.tab
                self.onRead?(read.tab)
                if let automationRequired = read.automationRequired {
                    self.onAutomationPermission?(automationRequired)
                }
                if self.readQueued {
                    self.readQueued = false
                    self.refresh()
                }
            }
        }
    }

    private static func read() -> (tab: Tab?, automationRequired: Bool?) {
        let running = Set(NSWorkspace.shared.runningApplications.compactMap(\.bundleIdentifier))
        let frontmost = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
        var automationRequired: Bool?
        for browser in candidates(running: running, frontmost: frontmost) {
            guard let script = NSAppleScript(source: script(for: browser)) else { continue }
            var error: NSDictionary?
            let result = script.executeAndReturnError(&error)
            if let number = error?[NSAppleScript.errorNumber] as? Int, number == -1743 {
                automationRequired = true
                continue
            }
            if error == nil {
                automationRequired = false
                if let url = url(fromAppleScriptResult: result.stringValue) {
                    return (Tab(browser: browser.name, url: url), automationRequired)
                }
            }
        }
        return (nil, automationRequired)
    }
}
