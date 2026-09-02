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
    private lazy var updaterController = SPUStandardUpdaterController(
        startingUpdater: true,
        updaterDelegate: self,
        userDriverDelegate: nil
    )
    private var peerIdsBeforePairing: Set<String>?
    private var peers: [Peer] = [] { didSet { scheduleStateRefresh() } }
    private var status = "Listening" { didSet { scheduleStateRefresh() } }
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

    func applicationDidFinishLaunching(_ notification: Notification) {
        _ = updaterController
        statusMenu.delegate = self
        statusMenu.autoenablesItems = false
        statusItem.isVisible = true
        statusItem.menu = statusMenu
        updateStatusIcon()

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }

        transport.onClipboard = { [weak self] text in
            self?.clipboard.applyRemote(text)
            let msg = L10n.tr("received_text")
            self?.notifyIncoming(title: "BinderClip", body: msg)
            ToastHUD.shared.show(message: msg, icon: "doc.on.clipboard.fill")
        }
        transport.onOpenURL = { [weak self] url in
            NSWorkspace.shared.open(url)
            let msg = L10n.tr("opened_link_in_browser")
            self?.notifyIncoming(title: "BinderClip", body: msg)
            ToastHUD.shared.show(message: msg, icon: "safari.fill")
        }
        transport.onImage = { [weak self] image in
            self?.clipboard.applyRemote(image)
            let msg = L10n.tr("received_image_format", image.mimeType)
            self?.notifyIncoming(title: "BinderClip", body: msg)
            ToastHUD.shared.show(message: msg, icon: "photo.fill")
        }
        transport.onPeersChanged = { [weak self] peers in
            self?.peers = peers
        }
        transport.onLog = { message in
            DiagnosticLog.shared.info(message)
        }
        transport.onTransferStatus = { [weak self] message in
            self?.status = message
        }
        transport.onLocalNetworkPermissionRequired = { [weak self] required in
            DispatchQueue.main.async { self?.localNetworkPermissionRequired = required }
        }

        clipboard.onLocalText = { [weak transport] text in
            transport?.sendClipboard(text)
        }
        clipboard.onLocalImage = { [weak transport] image in
            transport?.sendImage(image)
        }

        transport.start()
        clipboard.start()
        peers = transport.peersSnapshot()
        renderMenu()
        updateStatusIcon()

        #if DEBUG
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            if let invite = self?.transport.createInvite() {
                let debugDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("net.wastu.binderclip", isDirectory: true)
                try? FileManager.default.createDirectory(at: debugDir, withIntermediateDirectories: true)
                try? invite.absoluteString.write(to: debugDir.appendingPathComponent("debug-invite.txt"), atomically: true, encoding: .utf8)
                print("[BinderClip Debug] Ready pairing code: \(invite.absoluteString)")
            }
        }
        #endif
    }

    func applicationWillTerminate(_ notification: Notification) {
        clipboard.stop()
        transport.stop()
    }

    func menuWillOpen(_ menu: NSMenu) {
        isStatusMenuOpen = true
        cachedActiveTab = activeBrowserTab()
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

    private func notifyIncoming(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    private var isTransferring: Bool {
        status.contains("%") || status.hasPrefix("Sending image") || status.hasPrefix("Receiving image")
    }

    private func updateStatusIcon() {
        guard let button = statusItem.button else { return }
        button.image = binderClipStatusIcon()
        button.title = ""
        button.imagePosition = .imageOnly
        let hasConnectedPeers = peers.contains(where: \.connected)
        button.appearsDisabled = !hasConnectedPeers && !isTransferring
        if isTransferring {
            button.toolTip = "BinderClip: \(status)"
        } else if hasConnectedPeers {
            button.toolTip = "BinderClip: \(L10n.tr("status_connected"))"
        } else if peers.isEmpty {
            button.toolTip = "BinderClip: \(L10n.tr("listening"))"
        } else {
            button.toolTip = "BinderClip: \(L10n.tr("status_waiting_for_device"))"
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

    private func renderMenu() {
        let menu = statusMenu
        menu.removeAllItems()
        renderPendingPermissions(into: menu)

        if isTransferring {
            let progressItem = NSMenuItem(title: status, action: nil, keyEquivalent: "")
            progressItem.isEnabled = false
            menu.addItem(progressItem)
            menu.addItem(.separator())
        }

        addSectionHeader(hostCaption(), to: menu)

        if peers.isEmpty {
            let empty = NSMenuItem(title: L10n.tr("no_phones_yet"), action: nil, keyEquivalent: "")
            empty.isEnabled = false
            menu.addItem(empty)
        } else {
            let ordered = peers.filter(\.connected) + peers.filter { !$0.connected }
            for peer in ordered {
                let transport = transport.peerTransportType(peer.id)
                let isLive = peer.connected || transport != .none
                let title = isLive ? peer.name : L10n.tr("device_waiting_suffix", peer.name)
                let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
                item.image = NSImage(systemSymbolName: peerSymbolName(for: peer.platform), accessibilityDescription: peer.platform)
                if !isLive {
                    let attributed = NSMutableAttributedString(string: title)
                    attributed.addAttribute(.foregroundColor, value: NSColor.secondaryLabelColor, range: NSRange(location: 0, length: attributed.length))
                    item.attributedTitle = attributed
                }
                item.submenu = deviceMenu(for: peer)
                menu.addItem(item)
            }
        }

        menu.addItem(.separator())

        let pair = NSMenuItem(title: L10n.tr("add_device"), action: #selector(showPairing), keyEquivalent: "n")
        pair.target = self
        menu.addItem(pair)

        if let browserTab = cachedActiveTab {
            let sendURLItem = NSMenuItem(title: L10n.tr("send_browser_tab"), action: nil, keyEquivalent: "u")
            let urlSubmenu = NSMenu()
            urlSubmenu.autoenablesItems = false

            let truncatedUrlString = browserTab.url.absoluteString.count > 45 ? String(browserTab.url.absoluteString.prefix(42)) + "…" : browserTab.url.absoluteString
            let urlHeader = NSMenuItem(title: truncatedUrlString, action: nil, keyEquivalent: "")
            urlHeader.isEnabled = false
            urlSubmenu.addItem(urlHeader)
            urlSubmenu.addItem(.separator())

            let allItem = NSMenuItem(title: L10n.tr("all_connected_devices"), action: #selector(sendBrowserTabToTarget(_:)), keyEquivalent: "")
            allItem.target = self
            allItem.representedObject = ["url": browserTab.url, "peerId": nil as String? as Any]
            allItem.isEnabled = peers.contains(where: \.connected)
            urlSubmenu.addItem(allItem)

            if !peers.isEmpty {
                urlSubmenu.addItem(.separator())
                for peer in peers {
                    let peerItem = NSMenuItem(title: peer.name, action: #selector(sendBrowserTabToTarget(_:)), keyEquivalent: "")
                    peerItem.image = NSImage(systemSymbolName: peerSymbolName(for: peer.platform), accessibilityDescription: peer.platform)
                    peerItem.target = self
                    peerItem.representedObject = ["url": browserTab.url, "peerId": peer.id as String? as Any]
                    peerItem.isEnabled = peer.connected
                    urlSubmenu.addItem(peerItem)
                }
            }
            sendURLItem.submenu = urlSubmenu
            menu.addItem(sendURLItem)
        }

        menu.addItem(.separator())

        let more = NSMenuItem(title: L10n.tr("more"), action: nil, keyEquivalent: "")
        more.submenu = moreMenu()
        menu.addItem(more)

        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: L10n.tr("quit_binderclip"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
    }

    private func binderClipStatusIcon() -> NSImage? {
        let resource = Bundle.main.url(forResource: "BinderClipMenuIcon", withExtension: "svg")
            ?? Bundle.module.url(forResource: "BinderClipMenuIcon", withExtension: "svg")
        if let resource, let image = NSImage(contentsOf: resource) {
            image.size = NSSize(width: 18, height: 18)
            image.isTemplate = true
            return image
        }
        let image = NSImage(systemSymbolName: "paperclip", accessibilityDescription: "BinderClip")
        image?.isTemplate = true
        return image
    }

    private func renderPendingPermissions(into menu: NSMenu) {
        var hasPendingPermission = false
        if !transport.isBluetoothPoweredOn {
            let item = NSMenuItem(title: L10n.tr("enable_bluetooth"), action: #selector(openBluetoothSettings), keyEquivalent: "")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        } else if transport.isBluetoothPermissionDenied {
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

    private func moreMenu() -> NSMenu {
        let more = NSMenu()
        more.autoenablesItems = false

        let loginStatus = SMAppService.mainApp.status
        if loginStatus != .enabled {
            let needsApproval = loginStatus == .requiresApproval
            let login = NSMenuItem(
                title: needsApproval ? L10n.tr("allow_launch_at_login") : L10n.tr("enable_launch_at_login"),
                action: #selector(enableLaunchAtLogin),
                keyEquivalent: ""
            )
            login.target = self
            more.addItem(login)
            more.addItem(.separator())
        }

        let rename = NSMenuItem(title: L10n.tr("rename_this_mac"), action: #selector(renameDevice(_:)), keyEquivalent: "")
        rename.target = self
        rename.representedObject = transport.localDeviceID
        more.addItem(rename)

        if !peers.isEmpty {
            let unpairAll = NSMenuItem(title: L10n.tr("unpair_all_devices"), action: #selector(unpairAllDevices), keyEquivalent: "")
            unpairAll.target = self
            more.addItem(unpairAll)
        }

        let resetKey = NSMenuItem(title: L10n.tr("reset_pairing_key"), action: #selector(resetPairingKey), keyEquivalent: "")
        resetKey.target = self
        more.addItem(resetKey)

        more.addItem(.separator())

        let logs = NSMenuItem(title: L10n.tr("show_logs"), action: #selector(showLogs), keyEquivalent: "l")
        logs.target = self
        more.addItem(logs)

        let rawVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        #if DEBUG
        let versionString = rawVersion.map { $0.contains("debug") ? $0 : "\($0)-debug" } ?? "debug"
        let updatesTitle = "BinderClip Debug\tv\(versionString)"
        #else
        let versionString = rawVersion
        let updatesTitle = versionString.map { "\(L10n.tr("check_for_updates"))\tv\($0)" } ?? L10n.tr("check_for_updates")
        #endif
        let updates = NSMenuItem(title: updatesTitle, action: #selector(checkForUpdates), keyEquivalent: "")
        updates.target = self
        more.addItem(updates)
        return more
    }

    private func deviceMenu(for peer: Peer) -> NSMenu {
        let details = NSMenu()
        details.autoenablesItems = false
        let transportType = transport.peerTransportType(peer.id)
        let isConnected = peer.connected || transportType != .none
        let statusTitle: String
        if !isConnected || transportType == .none {
            statusTitle = L10n.tr("status_waiting_for_device")
        } else {
            switch transportType {
            case .bluetooth:
                statusTitle = L10n.tr("connected_via_bluetooth")
            case .mesh:
                statusTitle = L10n.tr("connected_via_mesh")
            case .lan:
                statusTitle = L10n.tr("connected_via_lan")
            case .none:
                statusTitle = L10n.tr("status_waiting_for_device")
            }
        }
        let status = NSMenuItem(title: statusTitle, action: nil, keyEquivalent: "")
        status.isEnabled = false
        details.addItem(status)
        let host = peer.endpoint.host.trimmingCharacters(in: .whitespacesAndNewlines)
        if !host.isEmpty, host != "unknown" {
            let ipTitle = host == "bluetooth" ? L10n.tr("transport_bluetooth_ble") : host
            let ip = NSMenuItem(title: ipTitle, action: nil, keyEquivalent: "")
            ip.isEnabled = false
            details.addItem(ip)
        }

        details.addItem(.separator())
        let sendToDevice = NSMenuItem(title: L10n.tr("send_clipboard_to_peer", peer.name), action: #selector(sendClipboardToPeer(_:)), keyEquivalent: "")
        sendToDevice.target = self
        sendToDevice.representedObject = peer.id
        sendToDevice.isEnabled = peer.connected
        details.addItem(sendToDevice)
        details.addItem(.separator())
        let rename = NSMenuItem(title: L10n.tr("rename_device_menu"), action: #selector(renameDevice(_:)), keyEquivalent: "")
        rename.target = self
        rename.representedObject = peer.id
        details.addItem(rename)
        let remove = NSMenuItem(title: L10n.tr("unpair_device_menu"), action: #selector(removePeer(_:)), keyEquivalent: "")
        remove.target = self
        remove.representedObject = peer.id
        details.addItem(remove)
        return details
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
        let peerName = peerID != nil ? (peers.first(where: { $0.id == peerID })?.name ?? "device") : L10n.tr("all_connected_devices")
        ToastHUD.shared.show(message: L10n.tr("sent_url_to_peer", peerName), icon: "safari.fill")
    }

    @objc private func sendClipboardToPeer(_ sender: NSMenuItem) {
        guard let peerID = sender.representedObject as? String else { return }
        let peerName = peers.first(where: { $0.id == peerID })?.name ?? "device"
        switch ClipboardClassifier.read(from: NSPasteboard.general) {
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

    @objc private func enableLaunchAtLogin() {
        let currentStatus = SMAppService.mainApp.status
        if currentStatus == .requiresApproval {
            SMAppService.openSystemSettingsLoginItems()
            renderMenu()
            return
        }
        do {
            try SMAppService.mainApp.register()
        } catch {
            DiagnosticLog.shared.error("Failed to register launch at login: \(error.localizedDescription)")
            SMAppService.openSystemSettingsLoginItems()
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
        updaterController.checkForUpdates(nil)
    }

    func feedURLString(for updater: SPUUpdater) -> String? {
        let base = Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") as? String
            ?? "https://github.com/bgwastu/BinderClip/releases/latest/download/appcast.xml"
        let timestamp = Int(Date().timeIntervalSince1970)
        return "\(base)?t=\(timestamp)"
    }
}
