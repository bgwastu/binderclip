import AppKit
import ServiceManagement
import Sparkle
import UserNotifications

/*
 THESIS: a private clipboard group is operated from one quiet menu, never a settings maze.
 OWN-WORLD: macOS system surfaces, BinderClip icon, a restrained teal action, semantic state color.
 STORY: users see connection truth, add a device with a time-boxed QR, then copy normally.
 FIRST VIEWPORT: the menu leads with live connection count, peers, then one pairing action.
 FORM: native menu-bar utility; compact operating panel rather than a dashboard.
*/
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate, SPUUpdaterDelegate {
    private let transport = WebSocketServer()
    private let clipboard = ClipboardBridge()
    private let pairing = PairingWindow()
    private let logWindow = LogWindowController()
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let statusIconView = StatusItemIconView()
    private var activeTransferProgress: TransferProgress?
    private lazy var updaterController = SPUStandardUpdaterController(
        startingUpdater: !AppProfile.current.isDebug,
        updaterDelegate: self,
        userDriverDelegate: nil
    )
    private var peerIdsBeforePairing: Set<String>?
    private var peers: [Peer] = [] { didSet { scheduleStateRefresh() } }
    private var status = "Listening" { didSet { scheduleStateRefresh() } }
    private var lastTransferStatus: String?
    private var localNetworkPermissionRequired = false { didSet { scheduleStateRefresh() } }
    private var automationPermissionRequired = false { didSet { scheduleStateRefresh() } }
    private var cachedActiveTab: (browser: String, url: URL)?
    private let statusMenu = NSMenu()
    private var isStatusMenuOpen = false
    private var menuNeedsRebuild = false
    private var pendingMenuAction: (() -> Void)?

    private func scheduleStateRefresh() {
        let apply = { [weak self] in
            guard let self else { return }
            self.updateStatusIcon()
            self.checkPairingCompletion()
            if self.isStatusMenuOpen {
                self.menuNeedsRebuild = true
                return
            }
            self.renderMenu()
        }
        if Thread.isMainThread {
            apply()
        } else {
            DispatchQueue.main.async(execute: apply)
        }
    }

    private var sleepObservers: [NSObjectProtocol] = []
    private var waitingForUserWake = false
    private lazy var debugClipboardWindow = DebugClipboardWindow(board: clipboard.pasteboard) { [weak self] in self?.clipboard.sendCurrentClipboard() }

    func applicationDidFinishLaunching(_ notification: Notification) {
        if !AppProfile.current.isDebug { _ = updaterController }
        statusMenu.delegate = self
        statusMenu.autoenablesItems = false
        statusItem.isVisible = true
        statusItem.menu = statusMenu
        if let button = statusItem.button {
            statusItem.length = 26
            button.image = NSImage(size: NSSize(width: 1, height: 18))
            statusIconView.setImage(binderClipStatusIcon())
            statusIconView.translatesAutoresizingMaskIntoConstraints = false
            button.addSubview(statusIconView)
            NSLayoutConstraint.activate([
                statusIconView.centerXAnchor.constraint(equalTo: button.centerXAnchor),
                statusIconView.centerYAnchor.constraint(equalTo: button.centerYAnchor),
                statusIconView.widthAnchor.constraint(equalToConstant: 18),
                statusIconView.heightAnchor.constraint(equalToConstant: 18)
            ])
        }
        updateStatusIcon()

        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }

        transport.onClipboard = { [weak self] text in
            guard self?.clipboard.applyRemote(text) == true else { return }
            let msg = L10n.tr("received_text")
            self?.notifyIncoming(title: "BinderClip", body: msg)
            ToastHUD.shared.show(message: msg, icon: "doc.on.clipboard.fill")
            self?.statusIconView.pulseSync()
        }
        transport.onOpenURL = { [weak self] url in
            if AppProfile.current.isDebug { self?.clipboard.applyRemote(url.absoluteString); return }
            NSWorkspace.shared.open(url)
            let msg = L10n.tr("opened_link_in_browser")
            self?.notifyIncoming(title: "BinderClip", body: msg)
            ToastHUD.shared.show(message: msg, icon: "safari.fill")
            self?.statusIconView.pulseSync()
        }
        transport.onImage = { [weak self] image in
            guard self?.clipboard.applyRemote(image) == true else { return }
            let msg = L10n.tr("received_image_format", image.mimeType)
            self?.notifyIncoming(title: "BinderClip", body: msg)
            ToastHUD.shared.show(message: msg, icon: "photo.fill")
            self?.statusIconView.pulseSync()
        }
        transport.onFile = { [weak self] url in
            let message = L10n.tr("file_received_format", url.lastPathComponent)
            self?.notifyIncoming(title: AppProfile.current.displayName, body: message, sound: .default, userInfo: ["fileURL": url.path])
            NSSound(named: "Glass")?.play()
            ToastHUD.shared.showFileReceived(url: url, message: message)
        }
        transport.onPeersChanged = { [weak self] peers in
            self?.peers = peers
            self?.clipboard.setSyncActive(!peers.isEmpty)
        }
        transport.onLog = { message in
            DiagnosticLog.shared.info(message)
        }
        transport.onTransferStatus = { [weak self] message in
            self?.lastTransferStatus = message
            self?.status = message
        }
        transport.onTransferProgress = { [weak self] progress in
            DispatchQueue.main.async {
                guard let self else { return }
                if progress.isActive {
                    self.activeTransferProgress = progress
                    self.statusIconView.transition(to: .progress(fraction: progress.fraction))
                } else {
                    let wasActive = self.activeTransferProgress != nil
                    self.activeTransferProgress = nil
                    if wasActive && progress.phase == .sent {
                        let kind = L10n.tr(progress.metadata.kind == "image" ? "transfer_kind_image" : "transfer_kind_file")
                        let name = progress.metadata.name.isEmpty ? kind : progress.metadata.name
                        let msg = L10n.tr("transfer_sent", name)
                        self.notifyIncoming(title: AppProfile.current.displayName, body: msg, sound: .default)
                        NSSound(named: "Glass")?.play()
                        if progress.metadata.kind != "file" { ToastHUD.shared.show(message: msg, icon: "checkmark.circle.fill") }
                    }
                    self.updateStatusIcon()
                }
                ToastHUD.shared.showTransfer(progress)
            }
        }
        transport.onLocalNetworkPermissionRequired = { [weak self] required in
            DispatchQueue.main.async { self?.localNetworkPermissionRequired = required }
        }

        transport.onPrepareClipboardSync = { [weak self] in self?.clipboard.captureChanges() }
        clipboard.onLocalText = { [weak transport] text, bootstrap in
            transport?.observeClipboard(text: text, bootstrap: bootstrap)
        }
        clipboard.onLocalImage = { [weak transport] image, bootstrap in
            transport?.observeClipboard(image: image, bootstrap: bootstrap)
        }
        clipboard.onLocalEmpty = { [weak transport] bootstrap in transport?.observeEmptyClipboard(bootstrap: bootstrap) }

        transport.start()
        clipboard.start()
        let workspaceNotifications = NSWorkspace.shared.notificationCenter
        sleepObservers.append(workspaceNotifications.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.waitingForUserWake = true
            self.clipboard.stop()
            self.transport.setSystemSleeping(true)
            self.statusIconView.resetToIdle(disabled: true)
            DiagnosticLog.shared.flush()
        })
        sleepObservers.append(workspaceNotifications.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.resumeAfterUserWake()
        })
        sleepObservers.append(workspaceNotifications.addObserver(forName: NSWorkspace.screensDidWakeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.resumeAfterUserWake()
        })
        peers = transport.peersSnapshot()
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        renderMenu()
        updateStatusIcon()

        #if DEBUG
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            if let invite = self?.transport.createInvite() {
                let debugDir = AppProfile.current.stateDirectory
                try? FileManager.default.createDirectory(at: debugDir, withIntermediateDirectories: true)
                try? invite.absoluteString.write(to: debugDir.appendingPathComponent("debug-invite.txt"), atomically: true, encoding: .utf8)
                try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: debugDir.appendingPathComponent("debug-invite.txt").path)
                print("[BinderClip Debug] Pairing code written to Application Support")
            }
        }
        #endif
    }

    private func resumeAfterUserWake() {
        // Maintenance wakes with a sleeping display must not re-arm the radio/clipboard loop.
        guard SystemWakePolicy.shouldResume(waiting: waitingForUserWake, displayAwake: CGDisplayIsAsleep(CGMainDisplayID()) == 0) else { return }
        waitingForUserWake = false
        transport.setSystemSleeping(false)
        clipboard.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        for observer in sleepObservers { NSWorkspace.shared.notificationCenter.removeObserver(observer) }
        DiagnosticLog.shared.flush()
        clipboard.stop()
        transport.stop()
    }

    func menuWillOpen(_ menu: NSMenu) {
        isStatusMenuOpen = true
        cachedActiveTab = AppProfile.current.isDebug ? nil : activeBrowserTab()
        renderMenu()
        menuNeedsRebuild = false
    }

    func menuDidClose(_ menu: NSMenu) {
        isStatusMenuOpen = false
        let pending = pendingMenuAction
        pendingMenuAction = nil
        if menuNeedsRebuild {
            menuNeedsRebuild = false
            renderMenu()
            updateStatusIcon()
        }
        if let pending {
            DispatchQueue.main.async(execute: pending)
        }
    }

    private func runAfterMenuCloses(_ action: @escaping () -> Void) {
        if isStatusMenuOpen {
            pendingMenuAction = action
            return
        }
        DispatchQueue.main.async(execute: action)
    }

    private func notifyIncoming(title: String, body: String, sound: UNNotificationSound? = .default, userInfo: [AnyHashable: Any] = [:]) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if let sound { content.sound = sound }
        content.userInfo = userInfo
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    private var isTransferring: Bool {
        status.contains("%") || status.hasPrefix("Sending image") || status.hasPrefix("Receiving image")
    }

    private func updateStatusIcon() {
        guard let button = statusItem.button else { return }
        statusIconView.setImage(binderClipStatusIcon())
        button.title = AppProfile.current.isDebug ? "D" : ""
        button.imagePosition = AppProfile.current.isDebug ? .imageLeft : .imageOnly
        let hasConnectedPeers = peers.contains(where: \.connected)
        let disabled = !hasConnectedPeers && !isTransferring
        button.appearsDisabled = disabled
        if isTransferring {
            button.toolTip = "\(AppProfile.current.displayName): \(status)"
            if let progress = activeTransferProgress, progress.fraction > 0 {
                statusIconView.transition(to: .progress(fraction: progress.fraction))
            } else {
                statusIconView.transition(to: .indeterminate)
            }
        } else {
            statusIconView.transition(to: .idle(appearsDisabled: disabled))
            if hasConnectedPeers {
                button.toolTip = "\(AppProfile.current.displayName): \(L10n.tr("status_connected"))"
            } else if peers.isEmpty {
                button.toolTip = "\(AppProfile.current.displayName): \(L10n.tr("listening"))"
            } else {
                button.toolTip = "\(AppProfile.current.displayName): \(L10n.tr("status_waiting_for_device"))"
            }
        }
    }

    private func hostCaption() -> String {
        let connected = peers.filter(\.connected)
        if !connected.isEmpty {
            return connected.count == 1 ? L10n.tr("connected_count_one") : L10n.tr("connected_count_many", connected.count)
        }
        if peers.isEmpty { return L10n.tr("listening") }
        if peers.count == 1 { return L10n.tr("waiting_for_device_named", peers[0].name) }
        return L10n.tr("waiting_for_devices")
    }

    private func addSectionHeader(_ title: String, to menu: NSMenu) {
        if #available(macOS 14.0, *) {
            menu.addItem(.sectionHeader(title: title))
        } else {
            let header = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            header.isEnabled = false
            menu.addItem(header)
        }
    }

    private func peerSymbolName(for platform: String) -> String {
        if platform == "macOS" { return "laptopcomputer" }
        if #available(macOS 14.0, *) { return "smartphone" }
        return "iphone"
    }

    private func connectionStatus(for peer: Peer) -> (text: String, live: Bool) {
        switch transport.peerTransportType(peer.id) {
        case .lan: return (L10n.tr("connected_via_lan"), true)
        case .mesh: return (L10n.tr("connected_via_mesh"), true)
        case .bluetooth: return (L10n.tr("connected_via_bluetooth"), true)
        case .none: return peer.connected ? (L10n.tr("status_connected"), true) : (L10n.tr("status_waiting_for_device"), false)
        }
    }

    /// One row per device; every action for it lives in its submenu.
    private func deviceItem(for peer: Peer) -> NSMenuItem {
        let status = connectionStatus(for: peer)
        let item = NSMenuItem(title: peer.name, action: nil, keyEquivalent: "")
        item.image = NSImage(systemSymbolName: peerSymbolName(for: peer.platform), accessibilityDescription: peer.platform)
        let submenu = NSMenu()
        submenu.autoenablesItems = false

        let caption = NSMenuItem(title: status.text, action: nil, keyEquivalent: "")
        caption.isEnabled = false
        submenu.addItem(caption)
        submenu.addItem(.separator())

        func action(_ title: String, _ selector: Selector, enabled: Bool = true) {
            let entry = NSMenuItem(title: title, action: selector, keyEquivalent: "")
            entry.target = self
            entry.representedObject = peer.id
            entry.isEnabled = enabled
            submenu.addItem(entry)
        }
        action(L10n.tr("send_clipboard"), #selector(sendClipboardToPeer(_:)), enabled: peer.connected)
        action(L10n.tr("send_file"), #selector(sendFileToPeer(_:)), enabled: transport.canSendFile(to: peer.id))
        if let tab = cachedActiveTab {
            let entry = NSMenuItem(title: L10n.tr("send_browser_tab"), action: #selector(sendBrowserTabToTarget(_:)), keyEquivalent: "")
            entry.target = self
            entry.representedObject = ["url": tab.url, "peerId": peer.id as String? as Any]
            entry.isEnabled = peer.connected
            entry.toolTip = tab.url.absoluteString
            submenu.addItem(entry)
        }
        submenu.addItem(.separator())
        action(L10n.tr("rename_device_menu"), #selector(renameDevice(_:)))
        action(L10n.tr("unpair_device_menu"), #selector(removePeer(_:)))
        item.submenu = submenu
        return item
    }

    private func renderMenu() {
        let menu = statusMenu
        menu.removeAllItems()
        renderPendingPermissions(into: menu)

        if lastTransferStatus != nil || isTransferring {
            let progressItem = NSMenuItem(title: status, action: nil, keyEquivalent: "")
            progressItem.isEnabled = false
            menu.addItem(progressItem)
            menu.addItem(.separator())
        }

        let headerText = AppProfile.current.isDebug
            ? "\(transport.localDeviceName) (Port \(AppProfile.current.port))"
            : transport.localDeviceName
        addSectionHeader(headerText, to: menu)

        if peers.isEmpty {
            let empty = NSMenuItem(title: L10n.tr("no_phones_yet"), action: nil, keyEquivalent: "")
            empty.isEnabled = false
            menu.addItem(empty)
        } else {
            for peer in peers.filter(\.connected) + peers.filter({ !$0.connected }) {
                menu.addItem(deviceItem(for: peer))
            }
        }

        menu.addItem(.separator())

        let pair = NSMenuItem(title: L10n.tr("add_device") + "…", action: #selector(showPairing), keyEquivalent: "n")
        pair.target = self
        menu.addItem(pair)

        let logs = NSMenuItem(title: L10n.tr("diagnostic_logs"), action: #selector(showLogs), keyEquivalent: "l")
        logs.target = self
        menu.addItem(logs)

        if AppProfile.current.isDebug {
            menu.addItem(.separator())
            let item = NSMenuItem(title: "Private Debug Clipboard…", action: #selector(showDebugClipboard), keyEquivalent: "d")
            item.target = self; menu.addItem(item)
            let bluetooth = NSMenuItem(title: "Bluetooth Testing (Shared Radio)", action: #selector(toggleDebugBluetooth), keyEquivalent: "")
            bluetooth.target = self
            bluetooth.state = transport.isBluetoothEnabled ? .on : .off
            menu.addItem(bluetooth)
        }

        menu.addItem(.separator())

        let login = NSMenuItem(title: L10n.tr("launch_at_login"), action: #selector(toggleLaunchAtLogin), keyEquivalent: "")
        login.target = self
        login.state = (!AppProfile.current.isDebug && SMAppService.mainApp.status == .enabled) ? .on : .off
        if AppProfile.current.isDebug { login.isEnabled = false }
        menu.addItem(login)

        let updates = NSMenuItem(title: L10n.tr("check_for_updates"), action: #selector(checkForUpdates), keyEquivalent: "")
        updates.target = self
        updates.isEnabled = !AppProfile.current.isDebug
        menu.addItem(updates)

        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: L10n.tr("quit_binderclip"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
    }

    @objc private func showDebugClipboard() { debugClipboardWindow.show() }

    @objc private func toggleDebugBluetooth() {
        transport.setDebugBluetoothEnabled(!transport.isBluetoothEnabled)
    }

    private func binderClipStatusIcon() -> NSImage? {
        let resource = Bundle.main.url(forResource: "BinderClipMenuIcon", withExtension: "svg")
            ?? Bundle.module.url(forResource: "BinderClipMenuIcon", withExtension: "svg")
        if let resource, let image = NSImage(contentsOf: resource) {
            // The glyph is cropped to its bounds, so size it by aspect instead of padding it inside a square.
            image.size = NSSize(width: 18 * 424 / 512, height: 18)
            image.isTemplate = true
            return image
        }
        let image = NSImage(systemSymbolName: "paperclip", accessibilityDescription: "BinderClip")
        image?.isTemplate = true
        return image
    }

    private func renderPendingPermissions(into menu: NSMenu) {
        var hasPendingPermission = false
        if transport.isBluetoothEnabled && !transport.isBluetoothPoweredOn {
            let item = NSMenuItem(title: L10n.tr("enable_bluetooth"), action: #selector(openBluetoothSettings), keyEquivalent: "")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        } else if transport.isBluetoothEnabled && transport.isBluetoothPermissionDenied {
            let item = NSMenuItem(title: L10n.tr("allow_bluetooth"), action: #selector(openBluetoothPermissionGuide), keyEquivalent: "")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if localNetworkPermissionRequired {
            let item = NSMenuItem(title: L10n.tr("allow_local_network"), action: #selector(openLocalNetworkPermissionGuide), keyEquivalent: "")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if clipboard.isAccessDenied {
            let item = NSMenuItem(title: L10n.tr("allow_clipboard_access"), action: #selector(openClipboardPermissionGuide), keyEquivalent: "")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if automationPermissionRequired {
            let item = NSMenuItem(title: L10n.tr("allow_browser_automation"), action: #selector(openAutomationPrivacySettings), keyEquivalent: "")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if hasPendingPermission { menu.addItem(.separator()) }
    }


    @objc private func showPairing() {
        runAfterMenuCloses { [weak self] in
            guard let self else { return }
            self.peerIdsBeforePairing = Set(self.peers.filter(\.connected).map(\.id))
            self.pairing.show(statusText: L10n.tr("pairing_waiting")) { [weak self] in self?.transport.createInvite() }
        }
    }

    @objc private func resetPairingKey() {
        let alert = NSAlert()
        alert.messageText = L10n.tr("dialog_generate_pairing_title")
        alert.informativeText = L10n.tr("dialog_generate_pairing_message")
        alert.addButton(withTitle: L10n.tr("dialog_generate_button"))
        alert.addButton(withTitle: L10n.tr("dialog_cancel_button"))
        if alert.runModal() == .alertFirstButtonReturn {
            transport.resetPairingKey()
            showPairing()
        }
    }

    private func checkPairingCompletion() {
        guard let before = peerIdsBeforePairing else { return }
        let connectedIds = Set(peers.filter(\.connected).map(\.id))
        if !connectedIds.subtracting(before).isEmpty {
            peerIdsBeforePairing = nil
            pairing.closeWithSuccess()
        }
    }

    /// Fast, safe active browser tab discovery without continuous background polling.
    private func activeBrowserTab() -> (browser: String, url: URL)? {
        let supportedBrowsers: [(bundleId: String, name: String, isChromium: Bool)] = [
            ("com.brave.Browser", "Brave", true),
            ("com.google.Chrome", "Google Chrome", true),
            ("com.google.Chrome.canary", "Google Chrome Canary", true),
            ("company.thebrowser.Browser", "Arc", true),
            ("com.microsoft.edgemac", "Microsoft Edge", true),
            ("com.vivaldi.Vivaldi", "Vivaldi", true),
            ("com.operasoftware.Opera", "Opera", true),
            ("com.kagi.kagisafari", "Orion", true),
            ("com.apple.Safari", "Safari", false),
            ("com.apple.SafariTechnologyPreview", "Safari Technology Preview", false),
        ]

        let runningApps = NSWorkspace.shared.runningApplications
        let runningBundleIDs = Set(runningApps.compactMap(\.bundleIdentifier))
        let runningBrowsers = supportedBrowsers.filter { runningBundleIDs.contains($0.bundleId) }
        guard !runningBrowsers.isEmpty else { return nil }

        let frontmostBundleID = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
        let sortedBrowsers = runningBrowsers.sorted { a, b in
            if a.bundleId == frontmostBundleID { return true }
            if b.bundleId == frontmostBundleID { return false }
            return false
        }

        // Only query the top 2 candidate browsers (frontmost first) to guarantee sub-millisecond execution
        for browser in sortedBrowsers.prefix(2) {
            let script: String
            if browser.isChromium {
                script = """
                tell application id "\(browser.bundleId)"
                    try
                        set u to URL of active tab of front window
                        if u is not "" then return u
                    end try
                end tell
                """
            } else {
                script = """
                tell application id "\(browser.bundleId)"
                    try
                        set u to URL of front document
                        if u is not "" then return u
                    end try
                end tell
                """
            }

            var error: NSDictionary?
            if let appleScript = NSAppleScript(source: script) {
                let result = appleScript.executeAndReturnError(&error)
                if let errNumber = error?[NSAppleScript.errorNumber] as? Int, errNumber == -1743 {
                    if !automationPermissionRequired { automationPermissionRequired = true }
                } else if error == nil {
                    if automationPermissionRequired { automationPermissionRequired = false }
                }
                if let str = result.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
                   let url = URL(string: str),
                   let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                    return (browser.name, url)
                }
            }
        }
        return nil
    }

    @objc private func sendBrowserTabToTarget(_ sender: NSMenuItem) {
        guard let dict = sender.representedObject as? [String: Any?],
              let url = dict["url"] as? URL else { return }
        let peerID = dict["peerId"] as? String
        transport.sendOpenURL(url, targetDeviceId: peerID)
        let peerName = peers.first(where: { $0.id == peerID })?.name ?? "device"
        ToastHUD.shared.show(message: L10n.tr("sent_url_to_peer", peerName), icon: "safari.fill")
    }

    @objc private func sendClipboardToPeer(_ sender: NSMenuItem) {
        guard let peerID = sender.representedObject as? String else { return }
        let peerName = peers.first(where: { $0.id == peerID })?.name ?? "device"
        switch ClipboardClassifier.read(from: clipboard.pasteboard) {
        case .text(let text):
            transport.sendClipboard(text, targetDeviceId: peerID)
            ToastHUD.shared.show(message: L10n.tr("sent_clipboard_to_peer", peerName), icon: "doc.on.clipboard.fill")
        case .image(let image):
            transport.sendImage(image, targetDeviceId: peerID)
            ToastHUD.shared.show(message: L10n.tr("sent_image_to_peer", peerName), icon: "photo.fill")
        case .unsupported:
            ToastHUD.shared.show(message: L10n.tr("nothing_to_send"), icon: "exclamationmark.circle")
        }
    }

    @objc private func showLogs() { logWindow.showWindow(nil) }

    @objc private func sendFileToPeer(_ sender: NSMenuItem) {
        guard let peerID = sender.representedObject as? String else { return }
        runAfterMenuCloses { [weak self] in
            guard let self else { return }
            let panel = NSOpenPanel()
            panel.canChooseDirectories = false
            panel.allowsMultipleSelection = true
            panel.prompt = L10n.tr("send_file")
            panel.message = L10n.tr("file_picker_help")
            guard panel.runModal() == .OK else { return }
            guard panel.urls.count <= 8, self.transport.canSendFile(to: peerID) else {
                ToastHUD.shared.show(message: L10n.tr("file_connection_required"), icon: "exclamationmark.circle")
                return
            }
            for url in panel.urls { self.transport.sendFile(url, targetDeviceID: peerID) }
        }
    }

    @objc private func renameDevice(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        let currentName = id == transport.localDeviceID ? transport.localDeviceName : peers.first(where: { $0.id == id })?.name
        guard let currentName else { return }
        let alert = NSAlert()
        alert.messageText = id == transport.localDeviceID ? L10n.tr("dialog_rename_this_mac_title") : L10n.tr("dialog_rename_device_title")
        alert.informativeText = L10n.tr("dialog_rename_prompt")
        let input = NSTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24))
        input.stringValue = currentName
        alert.accessoryView = input
        alert.addButton(withTitle: L10n.tr("dialog_save_button"))
        alert.addButton(withTitle: L10n.tr("dialog_cancel_button"))
        alert.window.initialFirstResponder = input
        if alert.runModal() == .alertFirstButtonReturn {
            let newName = input.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            if !newName.isEmpty {
                transport.renamePeer(id: id, newName: newName)
            }
        }
    }

    @objc private func unpairAllDevices() {
        let alert = NSAlert()
        alert.messageText = L10n.tr("dialog_unpair_all_title")
        alert.informativeText = L10n.tr("dialog_unpair_all_message")
        alert.addButton(withTitle: L10n.tr("dialog_unpair_button"))
        alert.addButton(withTitle: L10n.tr("dialog_cancel_button"))
        if alert.runModal() == .alertFirstButtonReturn { transport.unpairAll() }
    }

    @objc private func removePeer(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        let name = peers.first(where: { $0.id == id })?.name
        guard let name else { return }
        let alert = NSAlert()
        alert.messageText = L10n.tr("dialog_unpair_single_title", name)
        alert.informativeText = L10n.tr("dialog_unpair_single_message")
        alert.addButton(withTitle: L10n.tr("dialog_unpair_button"))
        alert.addButton(withTitle: L10n.tr("dialog_cancel_button"))
        if alert.runModal() == .alertFirstButtonReturn { transport.removePeer(id) }
    }

    @objc private func toggleLaunchAtLogin() {
        guard !AppProfile.current.isDebug else { return }
        let service = SMAppService.mainApp
        if service.status == .enabled {
            do {
                try service.unregister()
            } catch {
                DiagnosticLog.shared.error("Failed to unregister launch at login: \(error.localizedDescription)")
            }
        } else if service.status == .requiresApproval {
            SMAppService.openSystemSettingsLoginItems()
        } else {
            do {
                try service.register()
            } catch {
                DiagnosticLog.shared.error("Failed to register launch at login: \(error.localizedDescription)")
                SMAppService.openSystemSettingsLoginItems()
            }
        }
        renderMenu()
    }

    @objc private func openBluetoothSettings() {
        let alert = NSAlert()
        alert.messageText = L10n.tr("guide_bluetooth_title")
        alert.informativeText = L10n.tr("guide_bluetooth_message")
        alert.addButton(withTitle: L10n.tr("dialog_open_system_settings"))
        alert.addButton(withTitle: L10n.tr("dialog_done_button"))
        if alert.runModal() == .alertFirstButtonReturn {
            if let url = URL(string: "x-apple.systempreferences:com.apple.BluetoothSettings") {
                NSWorkspace.shared.open(url)
            } else {
                openPrivacySettings()
            }
        }
    }

    @objc private func openBluetoothPermissionGuide() {
        let alert = NSAlert()
        alert.messageText = L10n.tr("guide_bluetooth_permission_title")
        alert.informativeText = L10n.tr("guide_bluetooth_permission_message")
        alert.addButton(withTitle: L10n.tr("dialog_open_system_settings"))
        alert.addButton(withTitle: L10n.tr("dialog_done_button"))
        if alert.runModal() == .alertFirstButtonReturn {
            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth") {
                NSWorkspace.shared.open(url)
            } else {
                openPrivacySettings()
            }
        }
    }

    @objc private func openLocalNetworkPermissionGuide() {
        let alert = NSAlert()
        alert.messageText = L10n.tr("guide_local_network_title")
        alert.informativeText = L10n.tr("guide_local_network_message")
        alert.addButton(withTitle: L10n.tr("dialog_open_system_settings"))
        alert.addButton(withTitle: L10n.tr("dialog_done_button"))
        if alert.runModal() == .alertFirstButtonReturn {
            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") {
                NSWorkspace.shared.open(url)
            } else {
                openPrivacySettings()
            }
        }
    }

    @objc private func openClipboardPermissionGuide() {
        let alert = NSAlert()
        alert.messageText = L10n.tr("guide_clipboard_title")
        alert.informativeText = L10n.tr("guide_clipboard_message")
        alert.addButton(withTitle: L10n.tr("dialog_open_system_settings"))
        alert.addButton(withTitle: L10n.tr("dialog_done_button"))
        if alert.runModal() == .alertFirstButtonReturn {
            openPrivacySettings()
        }
    }

    @objc private func openPrivacySettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security") else { return }
        NSWorkspace.shared.open(url)
    }

    @objc private func openAutomationPrivacySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Automation") {
            NSWorkspace.shared.open(url)
        } else {
            openPrivacySettings()
        }
    }

    @objc private func checkForUpdates() {
        guard !AppProfile.current.isDebug else { return }
        updaterController.checkForUpdates(nil)
    }

    func feedURLString(for updater: SPUUpdater) -> String? {
        guard !AppProfile.current.isDebug else { return nil }
        let base = Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") as? String
            ?? "https://github.com/bgwastu/BinderClip/releases/latest/download/appcast.xml"
        let timestamp = Int(Date().timeIntervalSince1970)
        return "\(base)?t=\(timestamp)"
    }

    @objc func sendToPhones(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>?) {
        switch ClipboardClassifier.read(from: pboard) {
        case .text(let text):
            if let url = ServicesShare.webURL(from: text) {
                transport.sendOpenURL(url)
                ToastHUD.shared.show(message: L10n.tr("sent_url_to_phones"), icon: "safari.fill")
            } else {
                transport.sendClipboard(text)
                ToastHUD.shared.show(message: L10n.tr("sent_clipboard_to_phones"), icon: "doc.on.clipboard.fill")
            }
        case .image(let image):
            transport.sendImage(image)
            ToastHUD.shared.show(message: L10n.tr("sent_image_to_phones"), icon: "photo.fill")
        case .unsupported:
            break
        }
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if #available(macOS 11.0, *) {
            completionHandler([.banner, .sound])
        } else {
            completionHandler([.alert, .sound])
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if let path = response.notification.request.content.userInfo["fileURL"] as? String {
            let url = URL(fileURLWithPath: path)
            NSWorkspace.shared.activateFileViewerSelecting([url])
        }
        completionHandler()
    }
}
