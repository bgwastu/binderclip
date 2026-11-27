// App entry point: single-instance guard and NSApplication bootstrap.

#if !arch(arm64)
#error("BinderClip requires Apple Silicon")
#endif

import AppKit
import Darwin
import os

signal(SIGPIPE, SIG_IGN)

private let bootstrapLogger = Logger(subsystem: "net.wastu.binderclip", category: "Bootstrap")

private func hasAnotherRunningInstance() -> Bool {
    guard let bundleID = Bundle.main.bundleIdentifier else { return false }
    let currentPID = ProcessInfo.processInfo.processIdentifier
    return NSRunningApplication
        .runningApplications(withBundleIdentifier: bundleID)
        .contains { $0.processIdentifier != currentPID }
}

if hasAnotherRunningInstance() {
    bootstrapLogger.error("Another BinderClip instance detected; refusing secondary launch")
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.setActivationPolicy(.accessory)
app.delegate = delegate
app.run()
