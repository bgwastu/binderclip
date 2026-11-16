import AppKit

/// A visible, private clipboard for testing real transports beside production.
final class DebugClipboardWindow: NSWindowController, NSWindowDelegate {
    private let board: NSPasteboard
    private let send: () -> Void
    private let input = NSTextField(string: "")
    private let preview = NSTextField(wrappingLabelWithString: "Private clipboard is empty")
    private var timer: Timer?
    init(board: NSPasteboard, send: @escaping () -> Void) {
        self.board = board
        self.send = send
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 270),
                              styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = L10n.tr("debug_clip_window")
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.delegate = self
        let label = NSTextField(wrappingLabelWithString: L10n.tr("debug_clip_desc"))
        input.placeholderString = L10n.tr("debug_clip_field")
        let textButton = NSButton(title: L10n.tr("debug_clip_send_text"), target: self, action: #selector(sendText))
        let imageButton = NSButton(title: L10n.tr("debug_clip_send_image"), target: self, action: #selector(sendImage))
        let controls = NSStackView(views: [textButton, imageButton])
        controls.orientation = .horizontal
        let stack = NSStackView(views: [label, input, controls, preview])
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 18
        stack.translatesAutoresizingMaskIntoConstraints = false
        window.contentView!.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: window.contentView!.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: window.contentView!.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: window.contentView!.topAnchor, constant: 20),
            input.widthAnchor.constraint(equalTo: stack.widthAnchor),
            preview.widthAnchor.constraint(equalTo: stack.widthAnchor)
        ])
        window.center()
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func show() {
        showWindow(nil); NSApp.activate(ignoringOtherApps: true)
        refresh()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.refresh() }
        timer?.tolerance = 0.2
    }
    func windowWillClose(_ notification: Notification) { timer?.invalidate(); timer = nil }
    @objc private func sendText() {
        guard !input.stringValue.isEmpty else { return }
        board.clearContents(); board.setString(input.stringValue, forType: .string)
        send(); refresh()
    }
    @objc private func sendImage() {
        let panel = NSOpenPanel(); panel.allowsMultipleSelection = false; panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let temporary = NSPasteboard.withUniqueName(); defer { temporary.releaseGlobally() }
        temporary.writeObjects([url as NSURL])
        guard case .image(let image) = ClipboardClassifier.read(from: temporary) else { return }
        ImageClipboard.write(image, to: board); send(); refresh()
    }
    private func refresh() {
        switch ClipboardClassifier.read(from: board) {
        case .text(let text): preview.stringValue = L10n.tr("debug_clip_content", String(text.prefix(200)))
        case .image(let image): preview.stringValue = L10n.tr("debug_clip_image", image.mimeType, image.data.count)
        case .unsupported: preview.stringValue = L10n.tr("debug_clip_empty")
        }
    }
}
