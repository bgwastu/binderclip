import AppKit
import CryptoKit

final class ClipboardBridge {
    var onLocalText: ((String) -> Void)?
    var onLocalImage: ((ImagePayload) -> Void)?
    let pasteboard: NSPasteboard
    private var changeCount: Int
    private var echo = ClipboardEchoFilter()
    private var timer: Timer?
    private var syncActive = false
    private var running = false

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
        // Nothing can consume a clipboard change while disconnected. Preserve changeCount so
        // reconnection sends the latest content, without reading/decoding disconnected copies.
        guard running, syncActive else { return }
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in self?.poll() }
        timer.tolerance = 0.1
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func applyRemote(_ text: String) {
        echo.received("text:" + hash(text))
        pasteboard.clearContents(); pasteboard.setString(text, forType: .string)
        changeCount = pasteboard.changeCount
    }
    func applyRemote(_ image: ImagePayload) {
        echo.received("image:" + image.sha256)
        ImageClipboard.write(image, to: pasteboard)
        changeCount = pasteboard.changeCount
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
        switch ClipboardClassifier.read(from: pasteboard) {
        case .text(let text):
            guard text.utf8.count <= SyncProtocol.maximumTextBytes else { DiagnosticLog.shared.error("Clipboard text is too large"); return }
            onLocalText?(text)
        case .image(let image): onLocalImage?(image)
        case .unsupported: DiagnosticLog.shared.warning("Clipboard content is unsupported or unavailable")
        }
    }

    private func poll() {
        EnergyCounters.add("clipboard.poll")
        guard pasteboard.changeCount != changeCount else { return }
        changeCount = pasteboard.changeCount
        EnergyCounters.add("clipboard.decode")
        switch ClipboardClassifier.read(from: pasteboard) {
        case .image(let image):
            guard echo.shouldPublish("image:" + image.sha256) else { return }
            onLocalImage?(image)
        case .text(let text):
            guard text.utf8.count <= SyncProtocol.maximumTextBytes else { return }
            let digest = hash(text)
            guard echo.shouldPublish("text:" + digest) else { return }
            onLocalText?(text)
        case .unsupported: break
        }
    }
    private func hash(_ text: String) -> String { SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined() }
}


/// A local change consumes the prior echo marker, even when its type or value differs.
struct ClipboardEchoFilter {
    private var fingerprint: String?
    mutating func received(_ value: String) { fingerprint = value }
    mutating func shouldPublish(_ value: String) -> Bool {
        defer { fingerprint = nil }
        return value != fingerprint
    }
}
