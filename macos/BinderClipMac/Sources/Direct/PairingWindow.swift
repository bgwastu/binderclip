import AppKit
import CoreImage

final class PairingWindow: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var imageView: NSImageView?
    private var countdownLabel: NSTextField?
    private var statusLabel: NSTextField?
    private var endpointsLabel: NSTextField?
    private var invitationProvider: (() -> URL?)?
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

    private func buildWindow() {
        let title = NSTextField(labelWithString: L10n.tr("pairing_window_title"))
        title.font = .systemFont(ofSize: 20, weight: .semibold)
        title.alignment = .center
        let detail = NSTextField(labelWithString: L10n.tr("pairing_scan_detail"))
        detail.textColor = .secondaryLabelColor
        detail.alignment = .center
        statusLabel = detail
        let countdown = NSTextField(labelWithString: "5:00")
        countdown.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        countdown.textColor = .secondaryLabelColor
        countdown.alignment = .center
        countdownLabel = countdown
        let endpoints = NSTextField(labelWithString: "")
        endpoints.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        endpoints.textColor = .tertiaryLabelColor
        endpoints.alignment = .center
        endpoints.maximumNumberOfLines = 2
        endpoints.lineBreakMode = .byWordWrapping
        endpoints.preferredMaxLayoutWidth = 280
        endpointsLabel = endpoints

        let image = NSImageView()
        image.imageScaling = .scaleProportionallyUpOrDown
        image.wantsLayer = true
        image.layer?.backgroundColor = NSColor.white.cgColor
        image.translatesAutoresizingMaskIntoConstraints = false
        imageView = image
        let qrSurface = NSView()
        qrSurface.wantsLayer = true
        qrSurface.layer?.cornerRadius = 12
        qrSurface.layer?.masksToBounds = true
        qrSurface.layer?.backgroundColor = NSColor.white.cgColor
        qrSurface.translatesAutoresizingMaskIntoConstraints = false
        qrSurface.addSubview(image)
        NSLayoutConstraint.activate([
            image.leadingAnchor.constraint(equalTo: qrSurface.leadingAnchor, constant: 14),
            image.trailingAnchor.constraint(equalTo: qrSurface.trailingAnchor, constant: -14),
            image.topAnchor.constraint(equalTo: qrSurface.topAnchor, constant: 14),
            image.bottomAnchor.constraint(equalTo: qrSurface.bottomAnchor, constant: -14),
            qrSurface.widthAnchor.constraint(equalToConstant: 300),
            qrSurface.heightAnchor.constraint(equalToConstant: 300),
        ])

        let stack = NSStackView(views: [title, detail, qrSurface, endpoints, countdown])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 24, left: 24, bottom: 22, right: 24)
        let panel = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 348, height: 470), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        panel.title = "BinderClip"
        panel.level = .floating
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
        panel.contentView = stack
        panel.delegate = self
        window = panel
    }

    private func refreshInvite() {
        guard let url = invitationProvider?() else {
            statusLabel?.stringValue = L10n.tr("pairing_no_address")
            imageView?.image = nil
            endpointsLabel?.stringValue = ""
            return
        }
        guard let image = qr(url.absoluteString) else { return }
        imageView?.image = image
        if let info = SyncProtocol.parsePairingURL(url.absoluteString) {
            let hosts = info.endpoints.compactMap { SyncProtocol.parseEndpoint($0)?.host }
            endpointsLabel?.stringValue = hosts.joined(separator: "  ·  ")
        }
        expiresAt = Date().addingTimeInterval(300)
        #if DEBUG
        DiagnosticLog.shared.info("Pairing URL generated: \(url.absoluteString)")
        print("[BinderClip Debug] Pairing URL: \(url.absoluteString)")
        fflush(stdout)
        let debugDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("net.wastu.binderclip", isDirectory: true)
        try? FileManager.default.createDirectory(at: debugDir, withIntermediateDirectories: true)
        try? url.absoluteString.write(to: debugDir.appendingPathComponent("debug-invite.txt"), atomically: true, encoding: .utf8)
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
        countdownLabel?.stringValue = String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    private func stopTimer() { timer?.invalidate(); timer = nil }

    private func qr(_ content: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(content.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let extent = output.extent
        guard extent.width > 0, extent.height > 0, extent.width.isFinite, extent.height.isFinite else { return nil }
        let target: CGFloat = 272
        let scale = max(1, floor(min(target / extent.width, target / extent.height)))
        let scaled = output.transformed(by: .init(scaleX: scale, y: scale))
        let scaledExtent = scaled.extent
        guard scaledExtent.width > 0, scaledExtent.height > 0 else { return nil }
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cgImage = context.createCGImage(scaled, from: scaledExtent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }
}
