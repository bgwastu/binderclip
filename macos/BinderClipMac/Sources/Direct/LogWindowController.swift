import AppKit

final class LogWindowController: NSWindowController {
    private let textView = NSTextView()
    private var observer: NSObjectProtocol?

    init() {
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        textView.isEditable = false
        textView.isSelectable = true
        textView.isRichText = false
        textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        textView.textColor = NSColor(calibratedRed: 0.72, green: 0.92, blue: 0.79, alpha: 1)
        textView.backgroundColor = NSColor(calibratedWhite: 0.045, alpha: 1)
        textView.textContainerInset = NSSize(width: 12, height: 12)
        scroll.documentView = textView

        let clear = NSButton(title: L10n.tr("log_clear"), target: nil, action: nil)
        clear.bezelStyle = .rounded
        let copy = NSButton(title: L10n.tr("log_copy"), target: nil, action: nil)
        copy.bezelStyle = .rounded
        let close = NSButton(title: L10n.tr("log_close"), target: nil, action: nil)
        close.bezelStyle = .rounded
        let controls = NSStackView(views: [clear, copy, close])
        controls.orientation = .horizontal
        controls.alignment = .centerY
        controls.spacing = 8

        let content = NSStackView(views: [scroll, controls])
        content.orientation = .vertical
        content.spacing = 10
        content.edgeInsets = NSEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        content.setHuggingPriority(.defaultLow, for: .vertical)
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 300).isActive = true

        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 620, height: 430), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = L10n.tr("log_window_title")
        window.isReleasedWhenClosed = false
        window.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
        window.contentView = content
        window.minSize = NSSize(width: 440, height: 280)
        super.init(window: window)
        clear.target = self
        clear.action = #selector(clearLogs)
        copy.target = self
        copy.action = #selector(copyLogs)
        close.target = self
        close.action = #selector(closeWindow)
        // Never make the transport queue wait for AppKit's main thread. The transport
        // records connection events while launch is still rendering the menu.
        observer = NotificationCenter.default.addObserver(forName: DiagnosticLog.changed, object: DiagnosticLog.shared, queue: nil) { [weak self] _ in
            DispatchQueue.main.async { self?.refresh() }
        }
        refresh()
    }

    required init?(coder: NSCoder) { nil }
    deinit { observer.map(NotificationCenter.default.removeObserver) }

    override func showWindow(_ sender: Any?) {
        refresh()
        super.showWindow(sender)
        window?.center()
        window?.makeKeyAndOrderFront(sender)
        window?.orderFrontRegardless()
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func clearLogs() { DiagnosticLog.shared.clear() }
    @objc private func copyLogs() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(textView.string, forType: .string)
    }
    @objc private func closeWindow() { window?.performClose(nil) }

    private func refresh() {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let events = DiagnosticLog.shared.snapshot()
        textView.string = events.isEmpty ? L10n.tr("log_no_events") : events.map { "$ \(formatter.string(from: $0.date)) [\($0.level.rawValue.uppercased())] \($0.message)" }.joined(separator: "\n")
        textView.scrollToEndOfDocument(nil)
    }
}
