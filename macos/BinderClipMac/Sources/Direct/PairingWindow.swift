import AppKit
import CoreImage

final class PairingWindow: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var imageView: NSImageView?
    private var countdownLabel: NSTextField?
    private var statusLabel: NSTextField?
    private var endpointsLabel: NSTextField?
    private var copyButton: NSButton?
    private var invitationProvider: (() -> URL?)?
    private var currentPairingURL: URL?
    private var expiresAt = Date()
    private var timer: Timer?

    func show(statusText: String = L10n.tr("pairing_scan_detail"), invitationProvider: @escaping () -> URL?) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.show(statusText: statusText, invitationProvider: invitationProvider) }
            return
        }
        self.invitationProvider = invitationProvider
        if window == nil { buildWindow() }
        statusLabel?.stringValue = statusText
        statusLabel?.textColor = .secondaryLabelColor
        refreshInvite()
        window?.center()
        window?.makeKeyAndOrderFront(nil)
        window?.orderFrontRegardless()
        if #available(macOS 14.0, *) {
            NSApp.activate()
        } else {
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    func closeWithSuccess() {
        statusLabel?.stringValue = L10n.tr("pairing_success")
        statusLabel?.textColor = .systemGreen
        stopTimer()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            self?.window?.close()
            self?.statusLabel?.textColor = .secondaryLabelColor
        }
    }

    var isVisible: Bool { window?.isVisible ?? false }

    func windowWillClose(_ notification: Notification) { stopTimer() }

    private func makeStepRow(number: String, text: String) -> NSStackView {
        let badge = NSTextField(labelWithString: number)
        badge.font = .systemFont(ofSize: 11, weight: .bold)
        badge.alignment = .center
        badge.textColor = .secondaryLabelColor
        badge.wantsLayer = true
        badge.layer?.cornerRadius = 9
        badge.layer?.masksToBounds = true
        badge.layer?.backgroundColor = NSColor.quaternaryLabelColor.cgColor
        badge.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            badge.widthAnchor.constraint(equalToConstant: 18),
            badge.heightAnchor.constraint(equalToConstant: 18)
        ])

        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: 12, weight: .regular)
        label.textColor = .secondaryLabelColor
        label.lineBreakMode = .byWordWrapping
        label.maximumNumberOfLines = 2

        let row = NSStackView(views: [badge, label])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.spacing = 8
        return row
    }

    private func buildWindow() {
        let title = NSTextField(labelWithString: L10n.tr("pairing_window_title"))
        title.font = .systemFont(ofSize: 19, weight: .semibold)
        title.alignment = .center

        let detail = NSTextField(labelWithString: L10n.tr("pairing_scan_detail"))
        detail.font = .systemFont(ofSize: 13, weight: .regular)
        detail.textColor = .secondaryLabelColor
        detail.alignment = .center
        statusLabel = detail

        let image = NSImageView()
        image.imageScaling = .scaleProportionallyUpOrDown
        image.wantsLayer = true
        image.layer?.backgroundColor = NSColor.white.cgColor
        image.translatesAutoresizingMaskIntoConstraints = false
        imageView = image

        // Elevated QR code surface with rounded corners and high contrast
        let qrSurface = NSView()
        qrSurface.wantsLayer = true
        qrSurface.layer?.cornerRadius = 16
        qrSurface.layer?.backgroundColor = NSColor.white.cgColor
        qrSurface.layer?.shadowColor = NSColor.black.cgColor
        qrSurface.layer?.shadowOpacity = 0.12
        qrSurface.layer?.shadowOffset = CGSize(width: 0, height: -2)
        qrSurface.layer?.shadowRadius = 8
        qrSurface.layer?.borderWidth = 1
        qrSurface.layer?.borderColor = NSColor(white: 0, alpha: 0.1).cgColor
        qrSurface.translatesAutoresizingMaskIntoConstraints = false
        qrSurface.addSubview(image)

        NSLayoutConstraint.activate([
            image.leadingAnchor.constraint(equalTo: qrSurface.leadingAnchor, constant: 14),
            image.trailingAnchor.constraint(equalTo: qrSurface.trailingAnchor, constant: -14),
            image.topAnchor.constraint(equalTo: qrSurface.topAnchor, constant: 14),
            image.bottomAnchor.constraint(equalTo: qrSurface.bottomAnchor, constant: -14),
            qrSurface.widthAnchor.constraint(equalToConstant: 260),
            qrSurface.heightAnchor.constraint(equalToConstant: 260)
        ])

        // Countdown badge: "Expires in 4:59"
        let countdown = NSTextField(labelWithString: L10n.tr("pairing_expires_in", "5:00"))
        countdown.font = .monospacedDigitSystemFont(ofSize: 12, weight: .medium)
        countdown.textColor = .secondaryLabelColor
        countdown.alignment = .center
        countdownLabel = countdown

        // Prominent button: [ 📋 Copy Pairing Link ]
        let button = NSButton(title: L10n.tr("copy_pairing_link"), target: self, action: #selector(copyPairingLink))
        button.bezelStyle = .rounded
        button.controlSize = .large
        button.image = NSImage(systemSymbolName: "doc.on.doc", accessibilityDescription: nil)
        button.imagePosition = .imageLeading
        button.font = .systemFont(ofSize: 13, weight: .medium)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.widthAnchor.constraint(greaterThanOrEqualToConstant: 220).isActive = true
        copyButton = button

        // Endpoints info
        let endpoints = NSTextField(labelWithString: "")
        endpoints.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        endpoints.textColor = .tertiaryLabelColor
        endpoints.alignment = .center
        endpoints.maximumNumberOfLines = 2
        endpoints.lineBreakMode = .byWordWrapping
        endpoints.preferredMaxLayoutWidth = 280
        endpointsLabel = endpoints

        // Separator
        let box = NSBox()
        box.boxType = .separator
        box.translatesAutoresizingMaskIntoConstraints = false
        box.widthAnchor.constraint(equalToConstant: 280).isActive = true

        // Concise 3-step guide:
        // 1. Open BinderClip on your phone
        // 2. Tap Scan QR
        // 3. Point camera at this screen
        let guideStack = NSStackView(views: [
            makeStepRow(number: "1", text: L10n.tr("pairing_step_1")),
            makeStepRow(number: "2", text: L10n.tr("pairing_step_2")),
            makeStepRow(number: "3", text: L10n.tr("pairing_step_3"))
        ])
        guideStack.orientation = .vertical
        guideStack.alignment = .leading
        guideStack.spacing = 7
        guideStack.translatesAutoresizingMaskIntoConstraints = false
        guideStack.widthAnchor.constraint(equalToConstant: 260).isActive = true

        let stack = NSStackView(views: [
            title,
            detail,
            qrSurface,
            countdown,
            button,
            endpoints,
            box,
            guideStack
        ])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 22, left: 24, bottom: 22, right: 24)

        let panel = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 340, height: 570),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        panel.title = L10n.tr("pairing_window_title")
        panel.level = .floating
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
        panel.contentView = stack
        panel.delegate = self
        window = panel
    }

    @objc private func copyPairingLink() {
        guard let url = currentPairingURL else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(url.absoluteString, forType: .string)
        copyButton?.title = L10n.tr("pairing_link_copied")
        copyButton?.image = NSImage(systemSymbolName: "checkmark", accessibilityDescription: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { [weak self] in
            self?.copyButton?.title = L10n.tr("copy_pairing_link")
            self?.copyButton?.image = NSImage(systemSymbolName: "doc.on.doc", accessibilityDescription: nil)
        }
    }

    private func refreshInvite() {
        guard let url = invitationProvider?() else {
            currentPairingURL = nil
            statusLabel?.stringValue = L10n.tr("pairing_no_address")
            imageView?.image = nil
            endpointsLabel?.stringValue = ""
            copyButton?.isEnabled = false
            return
        }
        currentPairingURL = url
        copyButton?.isEnabled = true
        guard let image = qr(url.absoluteString) else { return }
        imageView?.image = image
        if let info = SyncProtocol.parsePairingURL(url.absoluteString) {
            let hosts = info.endpoints.compactMap { SyncProtocol.parseEndpoint($0)?.host }
            endpointsLabel?.stringValue = hosts.joined(separator: ", ")
        }
        expiresAt = Date().addingTimeInterval(300)
        #if DEBUG
        DiagnosticLog.shared.info("Pairing invitation generated")
        print("[BinderClip Debug] Pairing invitation saved privately")
        fflush(stdout)
        let debugDir = AppProfile.current.stateDirectory
        try? FileManager.default.createDirectory(at: debugDir, withIntermediateDirectories: true)
        try? url.absoluteString.write(to: debugDir.appendingPathComponent("debug-invite.txt"), atomically: true, encoding: .utf8)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: debugDir.appendingPathComponent("debug-invite.txt").path)
        #endif
        updateCountdown()
        stopTimer()
        let timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.updateCountdown() }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func updateCountdown() {
        let seconds = max(0, Int(expiresAt.timeIntervalSinceNow.rounded(.up)))
        if seconds == 0 { refreshInvite(); return }
        let timeString = String(format: "%d:%02d", seconds / 60, seconds % 60)
        countdownLabel?.stringValue = L10n.tr("pairing_expires_in", timeString)
    }

    private func stopTimer() { timer?.invalidate(); timer = nil }

    private func qr(_ content: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(content.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let extent = output.extent
        guard extent.width > 0, extent.height > 0, extent.width.isFinite, extent.height.isFinite else { return nil }
        let target: CGFloat = 232
        let scale = max(1, floor(min(target / extent.width, target / extent.height)))
        let scaled = output.transformed(by: .init(scaleX: scale, y: scale))
        let scaledExtent = scaled.extent
        guard scaledExtent.width > 0, scaledExtent.height > 0 else { return nil }
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cgImage = context.createCGImage(scaled, from: scaledExtent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }
}
