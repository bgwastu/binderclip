import AppKit

final class ClipboardBridge {
    var onLocalText: ((String, Bool) -> Void)?
    var onLocalImage: ((ImagePayload, Bool) -> Void)?
    var onLocalEmpty: ((Bool) -> Void)?
    let pasteboard: NSPasteboard
    private var changeCount: Int
    private var timer: Timer?
    private var syncActive = false
    private var running = false
    private var hasCaptured = false

    init(pasteboard: NSPasteboard = AppProfile.current.pasteboard) {
        self.pasteboard = pasteboard
        changeCount = pasteboard.changeCount
    }

    var isAccessDenied: Bool {
        guard #available(macOS 15.4, *) else { return false }
        let sel = NSSelectorFromString("accessBehavior")
        guard pasteboard.responds(to: sel),
              let value = pasteboard.value(forKey: "accessBehavior") as? NSNumber else {
            return false
        }
        return value.intValue == 1
    }

    func start() {
        running = true
        configureTimer()
    }
    func stop() { running = false; timer?.invalidate(); timer = nil }
    func setSyncActive(_ active: Bool) {
        guard active != syncActive else { return }
        syncActive = active
        if running { configureTimer() }
    }
    private func configureTimer() {
        timer?.invalidate()
        timer = nil
        // Track copies while paired, including disconnected periods, so their version reflects
        // the copy time rather than the time a network connection happens to return.
        guard running, syncActive else { return }
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in self?.poll() }
        timer.tolerance = 0.1
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    @discardableResult func applyRemote(_ text: String) -> Bool {
        guard pasteboard.changeCount == changeCount else { captureChanges(); return false }
        let generation = pasteboard.declareTypes([.string], owner: nil)
        pasteboard.setString(text, forType: .string)
        changeCount = generation
        hasCaptured = true
        guard pasteboard.changeCount == generation else { captureChanges(); return false }
        return true
    }
    @discardableResult func applyRemote(_ image: ImagePayload) -> Bool {
        guard pasteboard.changeCount == changeCount else { captureChanges(); return false }
        guard let generation = ImageClipboard.write(image, to: pasteboard, expectedChangeCount: changeCount) else { captureChanges(); return false }
        changeCount = generation
        hasCaptured = true
        guard pasteboard.changeCount == generation else { captureChanges(); return false }
        return true
    }

    func currentPreviewDescription() -> String? {
        switch ClipboardClassifier.read(from: pasteboard) {
        case .text(let text):
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return nil }
            let singleLine = trimmed.components(separatedBy: .newlines).first ?? trimmed
            let preview = singleLine.count > 30 ? String(singleLine.prefix(27)) + "…" : singleLine
            return "“\(preview)”"
        case .image(let image):
            let kb = image.data.count / 1024
            let sizeStr = kb >= 1024 ? String(format: "%.1f MB", Double(kb) / 1024.0) : "\(kb) KB"
            return "Image (\(image.mimeType.replacingOccurrences(of: "image/", with: "").uppercased()), \(sizeStr))"
        case .unsupported:
            return nil
        }
    }

    func sendCurrentClipboard() {
        changeCount = pasteboard.changeCount
        hasCaptured = true
        switch ClipboardClassifier.read(from: pasteboard) {
        case .text(let text):
            guard text.utf8.count <= SyncProtocol.maximumTextBytes else { DiagnosticLog.shared.error("Clipboard text is too large"); return }
            onLocalText?(text, false)
        case .image(let image): onLocalImage?(image, false)
        case .unsupported: DiagnosticLog.shared.warning("Clipboard content is unsupported or unavailable")
        }
    }

    func captureChanges() { poll() }

    private func poll() {
        EnergyCounters.add("clipboard.poll")
        guard !isAccessDenied, !hasCaptured || pasteboard.changeCount != changeCount else { return }
        let bootstrap = !hasCaptured
        hasCaptured = true
        changeCount = pasteboard.changeCount
        EnergyCounters.add("clipboard.decode")
        switch ClipboardClassifier.read(from: pasteboard) {
        case .image(let image):
            onLocalImage?(image, bootstrap)
        case .text(let text):
            guard text.utf8.count <= SyncProtocol.maximumTextBytes else { return }
            onLocalText?(text, bootstrap)
        case .unsupported: onLocalEmpty?(bootstrap)
        }
    }
}
