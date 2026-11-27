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
    private enum ClipboardItem {
        case text(String)
        case image(ImagePayload)
    }
    private var pendingClipboard: ClipboardItem? { didSet { scheduleStateRefresh() } }
    private var pendingSharedTexts = [String]() { didSet { scheduleStateRefresh() } }
    private let transferHistory = TransferHistory()
    private lazy var transfersWindow = TransfersWindow(history: transferHistory, folder: transport.receivedFolder) { [weak self] peerID, id in
        self?.transport.retryFile(peerID: peerID, id: id)
    }
    private var activeTransfers = [String: TransferProgress]()
    private var activeTransferProgress: TransferProgress? { activeTransfers.values.first }
    private var autoApplyClipboard: Bool {
        AppProfile.current.defaults.object(forKey: "auto-apply-clipboard") as? Bool ?? AppProfile.current.isDebug
    }
    private var transferNotifications: Bool {
        AppProfile.current.defaults.object(forKey: "transfer-notifications") as? Bool ?? true
    }
    private lazy var updaterController = SPUStandardUpdaterController(
        startingUpdater: !AppProfile.current.isDebug,
        updaterDelegate: self,
        userDriverDelegate: nil
    )
    private var peerIdsBeforePairing: Set<String>?
    private var peers: [Peer] = [] { didSet { scheduleStateRefresh() } }
    private var status = "" { didSet { scheduleStateRefresh() } }
    private var lastTransferStatus: String?
    private var localNetworkPermissionRequired = false { didSet { scheduleStateRefresh() } }
    private var automationPermissionRequired = false { didSet { scheduleStateRefresh() } }
    private let browserTabs = BrowserTabReader()
    private var cachedActiveTab: BrowserTabReader.Tab?
    private let statusMenu = NSMenu()
    private var isStatusMenuOpen = false
    private var menuNeedsRebuild = false
    private var pendingMenuAction: (() -> Void)?

    private var stateRefreshScheduled = false

    /// Coalesces bursts (e.g. one status per transfer chunk) into at most ~10 menu rebuilds a second.
    private func scheduleStateRefresh() {
        let apply = { [weak self] in
            guard let self else { return }
            self.stateRefreshScheduled = false
            self.updateStatusIcon()
            self.checkPairingCompletion()
            if self.isStatusMenuOpen {
                self.menuNeedsRebuild = true
                return
            }
            self.renderMenu()
        }
        let schedule = { [weak self] in
            guard let self, !self.stateRefreshScheduled else { return }
            self.stateRefreshScheduled = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1, execute: apply)
        }
        if Thread.isMainThread { schedule() } else { DispatchQueue.main.async(execute: schedule) }
    }

    private var workspaceObservers: [NSObjectProtocol] = []
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

        transport.onClipboard = { [weak self] text in self?.receiveClipboard(.text(text)) }
        transport.onImage = { [weak self] image in self?.receiveClipboard(.image(image)) }
        transport.onSharedText = { [weak self] text in self?.queueSharedText(text) ?? false }
        transport.onOpenURL = { [weak self] url in
            guard !AppProfile.current.isDebug, SyncProtocol.isBrowsableURL(url) else {
                _ = self?.queueSharedText(url.absoluteString)
                return
            }
            NSWorkspace.shared.open(url)
            ToastHUD.shared.show(message: L10n.tr("opened_link_in_browser"), icon: "safari.fill")
            self?.statusIconView.pulseSync()
        }
        transport.onTransferBatch = { [weak self] snapshot, peerID in
            guard let self, let peer = self.peers.first(where: { $0.id == peerID }) else { return }
            if let batch = self.transferHistory.apply(snapshot, peerID: peerID, name: peer.name) {
                self.notifyTransfer(batch)
            }
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
            guard let self, let peer = self.peers.first(where: { $0.id == progress.peerID }) else { return }
            self.activeTransfers[progress.identity] = progress.isActive ? progress : nil
            if progress.metadata.kind == "file" {
                if let batch = self.transferHistory.apply(progress, name: peer.name) {
                    self.notifyTransfer(batch)
                }
            } else if progress.phase == .sent {
                ToastHUD.shared.show(message: progress.message, icon: progress.icon)
            }
            self.updateStatusIcon()
        }
        transport.onLocalNetworkPermissionRequired = { [weak self] required in
            DispatchQueue.main.async { self?.localNetworkPermissionRequired = required }
        }
        browserTabs.onAutomationPermission = { [weak self] required in
            self?.automationPermissionRequired = required
        }
        browserTabs.onRead = { [weak self] tab in self?.applyBrowserTab(tab) }
        if !AppProfile.current.isDebug { browserTabs.refresh() }

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
        workspaceObservers.append(workspaceNotifications.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.waitingForUserWake = true
            self.clipboard.stop()
            self.transport.setSystemSleeping(true)
            self.statusIconView.resetToIdle(disabled: true)
            DiagnosticLog.shared.flush()
        })
        workspaceObservers.append(workspaceNotifications.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.resumeAfterUserWake()
        })
        workspaceObservers.append(workspaceNotifications.addObserver(forName: NSWorkspace.screensDidWakeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.resumeAfterUserWake()
        })
        // Keep the cached tab warm so opening the menu rarely shows a page the user already left.
        workspaceObservers.append(workspaceNotifications.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] note in
            guard let self, !AppProfile.current.isDebug else { return }
            let bundleId = (note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication)?.bundleIdentifier
            guard let bundleId, BrowserTabReader.isSupported(bundleId: bundleId) else { return }
            self.browserTabs.refresh(minimumInterval: 1)
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
        for observer in workspaceObservers { NSWorkspace.shared.notificationCenter.removeObserver(observer) }
        DiagnosticLog.shared.flush()
        clipboard.stop()
        transport.stop()
    }

    func menuWillOpen(_ menu: NSMenu) {
        isStatusMenuOpen = true
        // Render from the last read so the menu opens instantly, then refresh for the next open.
        cachedActiveTab = AppProfile.current.isDebug ? nil : browserTabs.cached
        renderMenu()
        menuNeedsRebuild = false
        if !AppProfile.current.isDebug { browserTabs.refresh() }
    }

    /// A browser read finished on the background queue: keep the cache current, and patch the items
    /// of an already open menu in place rather than rebuilding it under the pointer.
    private func applyBrowserTab(_ tab: BrowserTabReader.Tab?) {
        cachedActiveTab = tab
        guard isStatusMenuOpen, let tab else { return }
        for menuItem in statusMenu.items {
            guard let submenu = menuItem.submenu else { continue }
            for item in submenu.items where item.action == #selector(sendBrowserTabToTarget(_:)) {
                guard let target = item.representedObject as? BrowserTabTarget else { continue }
                item.representedObject = BrowserTabTarget(url: tab.url, peerId: target.peerId)
                item.toolTip = tab.url.absoluteString
            }
        }
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

    private func receiveClipboard(_ item: ClipboardItem) {
        let applied: Bool
        switch item {
        case .text(let text): applied = autoApplyClipboard && clipboard.applyRemote(text)
        case .image(let image): applied = autoApplyClipboard && clipboard.applyRemote(image)
        }
        pendingClipboard = applied ? nil : item
        if applied {
            statusIconView.pulseSync()
            let message: String
            switch item {
            case .text: message = L10n.tr("received_text")
            case .image(let image): message = L10n.tr("received_image_format", image.mimeType)
            }
            ToastHUD.shared.show(message: message, icon: "doc.on.clipboard.fill")
        }
    }

    private func notifyTransfer(_ batch: TransferHistory.Batch) {
        guard transferNotifications else { return }
        notifyIncoming(title: batch.peerName, body: L10n.tr("transfers_saved_count", batch.saved, batch.count),
                       userInfo: ["showTransfers": true])
    }

    @objc private func copyReceivedClipboard() {
        guard let pendingClipboard else { return }
        let applied: Bool
        switch pendingClipboard {
        case .text(let text): applied = clipboard.copyReceived(text)
        case .image(let image): applied = clipboard.copyReceived(image)
        }
        if applied { self.pendingClipboard = nil }
    }

    private func queueSharedText(_ text: String) -> Bool {
        guard pendingSharedTexts.count < 32 else { return false }
        pendingSharedTexts.append(text)
        ToastHUD.shared.show(message: L10n.tr("shared_text_ready"), icon: "doc.on.clipboard")
        return true
    }

    @objc private func copySharedText() {
        if let text = pendingSharedTexts.first, clipboard.copyReceived(text) { pendingSharedTexts.removeFirst() }
    }

    @objc private func toggleAutoApplyClipboard() {
        AppProfile.current.defaults.set(!autoApplyClipboard, forKey: "auto-apply-clipboard")
        scheduleStateRefresh()
    }

    @objc private func toggleTransferNotifications() {
        AppProfile.current.defaults.set(!transferNotifications, forKey: "transfer-notifications")
        scheduleStateRefresh()
    }

    @objc private func showTransfers() {
        runAfterMenuCloses { [weak self] in
            self?.transfersWindow.showWindow(nil)
            NSApp.activate(ignoringOtherApps: true)
        }
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

    /// Driven by typed progress, never by parsing the (localized) status text.
    private var isTransferring: Bool { activeTransferProgress != nil }

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
            entry.representedObject = BrowserTabTarget(url: tab.url, peerId: peer.id)
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

        func command(_ key: String, _ selector: Selector) -> NSMenuItem {
            let item = NSMenuItem(title: L10n.tr(key), action: selector, keyEquivalent: "")
            item.target = self
            menu.addItem(item)
            return item
        }
        _ = command("transfers_title", #selector(showTransfers))
        command("transfers_notify", #selector(toggleTransferNotifications)).state = transferNotifications ? .on : .off
        command("clipboard_auto_apply", #selector(toggleAutoApplyClipboard)).state = autoApplyClipboard ? .on : .off
        if pendingClipboard != nil { _ = command("clipboard_copy_received", #selector(copyReceivedClipboard)) }
        if !pendingSharedTexts.isEmpty { _ = command("clipboard_copy_shared", #selector(copySharedText)) }

        let logs = NSMenuItem(title: L10n.tr("diagnostic_logs"), action: #selector(showLogs), keyEquivalent: "l")
        logs.target = self
        menu.addItem(logs)

        // Revocation: a leaked QR stays valid until the key rotates, so keep both reachable.
        if !peers.isEmpty {
            let unpairAll = NSMenuItem(title: L10n.tr("unpair_all_devices"), action: #selector(unpairAllDevices), keyEquivalent: "")
            unpairAll.target = self
            menu.addItem(unpairAll)
        }
        let resetKey = NSMenuItem(title: L10n.tr("reset_pairing_key"), action: #selector(resetPairingKey), keyEquivalent: "")
        resetKey.target = self
        menu.addItem(resetKey)

        if AppProfile.current.isDebug {
            menu.addItem(.separator())
            let item = NSMenuItem(title: L10n.tr("debug_clip_menu"), action: #selector(showDebugClipboard), keyEquivalent: "d")
            item.target = self; menu.addItem(item)
            let bluetooth = NSMenuItem(title: L10n.tr("debug_bt_menu"), action: #selector(toggleDebugBluetooth), keyEquivalent: "")
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
            transferHistory.clear()
            pendingClipboard = nil
            pendingSharedTexts.removeAll()
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

    @objc private func sendBrowserTabToTarget(_ sender: NSMenuItem) {
        guard let target = sender.representedObject as? BrowserTabTarget else { return }
        let url = target.url
        let peerID = target.peerId
        transport.sendOpenURL(url, targetDeviceId: peerID)
        let peerName = peers.first(where: { $0.id == peerID })?.name ?? "device"
        ToastHUD.shared.show(message: L10n.tr("sent_url_to_peer", peerName), icon: "safari.fill")
    }

    @objc private func sendClipboardToPeer(_ sender: NSMenuItem) {
        guard let peerID = sender.representedObject as? String else { return }
        let peerName = peers.first(where: { $0.id == peerID })?.name ?? "device"
        switch ClipboardClassifier.read(from: clipboard.pasteboard) {
        case .text(let text) where text.utf8.count > SyncProtocol.maximumTextBytes:
            ToastHUD.shared.show(message: L10n.tr("text_too_large"), icon: "exclamationmark.circle")
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
            guard panel.urls.count <= TransferBatch.maximumFiles else {
                ToastHUD.shared.show(message: L10n.tr("file_picker_help"), icon: "exclamationmark.circle")
                return
            }
            self.transport.sendFiles(panel.urls, targetDeviceID: peerID)
            self.showTransfers()
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
        if alert.runModal() == .alertFirstButtonReturn {
            transport.unpairAll()
            transferHistory.clear()
            pendingClipboard = nil
            pendingSharedTexts.removeAll()
        }
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
        if alert.runModal() == .alertFirstButtonReturn {
            transport.removePeer(id)
            transferHistory.clear(peerID: id)
            pendingClipboard = nil
            pendingSharedTexts.removeAll()
        }
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
        case .text(let text) where text.utf8.count > SyncProtocol.maximumTextBytes:
            ToastHUD.shared.show(message: L10n.tr("text_too_large"), icon: "exclamationmark.circle")
        case .text(let text):
            if let url = ServicesShare.webURL(from: text) {
                transport.sendOpenURL(url)
                ToastHUD.shared.show(message: L10n.tr("sent_url_to_phones"), icon: "safari.fill")
            } else {
                transport.sendSharedText(text)
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
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if response.notification.request.content.userInfo["showTransfers"] as? Bool == true {
            DispatchQueue.main.async { [weak self] in self?.showTransfers() }
        }
        completionHandler()
    }
}
