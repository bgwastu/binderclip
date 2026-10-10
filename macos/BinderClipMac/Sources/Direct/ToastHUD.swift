import AppKit
import SwiftUI

private enum ToastCategory {
    case clipboard
    case browser
    case media
    case alert
    case standard

    static func from(icon: String) -> ToastCategory {
        if icon.contains("clipboard") {
            return .clipboard
        } else if icon.contains("safari") || icon.contains("link") || icon.contains("globe") {
            return .browser
        } else if icon.contains("photo") || icon.contains("image") {
            return .media
        } else if icon.contains("exclamationmark") {
            return .alert
        } else {
            return .standard
        }
    }

    var color: Color {
        switch self {
        case .clipboard:
            return Color(nsColor: .systemGreen)
        case .browser:
            return Color(nsColor: .systemBlue)
        case .media:
            return Color(nsColor: .systemPurple)
        case .alert:
            return Color(nsColor: .systemOrange)
        case .standard:
            return Color(nsColor: .controlAccentColor)
        }
    }
}

private struct ToastCapsuleView: View {
    let message: String
    let icon: String
    var progress: TransferProgress? = nil
    @Environment(\.colorScheme) private var colorScheme

    private var category: ToastCategory {
        ToastCategory.from(icon: icon)
    }

    var body: some View {
        HStack(spacing: 10) {
            // Category-tinted circular icon platter
            ZStack {
                Circle()
                    .fill(category.color.opacity(colorScheme == .dark ? 0.22 : 0.14))
                    .overlay(
                        Circle()
                            .strokeBorder(
                                category.color.opacity(colorScheme == .dark ? 0.38 : 0.24),
                                lineWidth: 0.5
                            )
                    )
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(category.color)
            }
            .frame(width: 28, height: 28)

            if let progress {
                VStack(alignment: .leading, spacing: 5) {
                    Text(message)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(progress.metadata.name)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    ProgressView(value: progress.fraction)
                        .progressViewStyle(.linear)
                        .tint(category.color)
                    Text(byteCount(progress.completedBytes) + " / " + byteCount(progress.metadata.size))
                        .font(.system(size: 10).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .frame(width: 300, alignment: .leading)
            } else {
                Text(message)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: 380, alignment: .leading)
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.vertical, progress == nil ? 7 : 12)
        .liquidGlassBacking(colorScheme: colorScheme, cornerRadius: progress == nil ? 100 : 20)
        .padding(14) // Breathing room for the ambient drop shadow
    }

    private func byteCount(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct LiquidGlassModifier: ViewModifier {
    let colorScheme: ColorScheme
    let cornerRadius: CGFloat

    func body(content: Content) -> some View {
        content
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        Color.white.opacity(colorScheme == .dark ? 0.16 : 0.48),
                        lineWidth: 0.5
                    )
            )
            .shadow(
                color: Color.black.opacity(colorScheme == .dark ? 0.36 : 0.12),
                radius: 14,
                x: 0,
                y: 4
            )
    }
}

private extension View {
    func liquidGlassBacking(colorScheme: ColorScheme, cornerRadius: CGFloat) -> some View {
        modifier(LiquidGlassModifier(colorScheme: colorScheme, cornerRadius: cornerRadius))
    }
}

private final class ToastPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
    override var isKeyWindow: Bool { true }
}

final class ToastHUD {
    static let shared = ToastHUD()
    private var window: ToastPanel?
    private var hostingView: NSHostingView<ToastCapsuleView>?
    private var dismissWorkItem: DispatchWorkItem?
    private var progressWorkItem: DispatchWorkItem?
    private var activeTransfers: [String: TransferProgress] = [:]
    private var transferOrder: [String] = []
    private var currentTransferID: String?
    private var displayedTransfer: TransferProgress?
    private var lastProgressUpdate: TimeInterval = 0

    func show(message: String, icon: String = "checkmark.circle.fill") {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.activeTransfers.isEmpty else { return }
            self.displayedTransfer = nil
            self.render(ToastCapsuleView(message: message, icon: icon), dismiss: true)
        }
    }

    func showTransfer(_ progress: TransferProgress) {
        guard progress.metadata.kind == "file" else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let id = progress.identity
            if progress.isActive {
                if self.activeTransfers[id] == nil { self.transferOrder.append(id) }
                self.activeTransfers[id] = progress
                if self.currentTransferID == nil { self.currentTransferID = id }
            } else {
                self.activeTransfers.removeValue(forKey: id)
                self.transferOrder.removeAll { $0 == id }
                if self.currentTransferID == id { self.currentTransferID = self.transferOrder.first }
            }

            guard let currentID = self.currentTransferID, let current = self.activeTransfers[currentID] else {
                self.progressWorkItem?.cancel()
                self.progressWorkItem = nil
                self.display(progress)
                return
            }
            guard currentID == id || self.displayedTransfer?.identity != currentID else { return }
            let samePhase = self.displayedTransfer?.identity == currentID && self.displayedTransfer?.phase == current.phase
            let elapsed = ProcessInfo.processInfo.systemUptime - self.lastProgressUpdate
            if samePhase, elapsed < 0.1 {
                guard self.progressWorkItem == nil else { return }
                let work = DispatchWorkItem { [weak self] in
                    guard let self else { return }
                    self.progressWorkItem = nil
                    guard let id = self.currentTransferID, let latest = self.activeTransfers[id] else { return }
                    self.display(latest)
                }
                self.progressWorkItem = work
                DispatchQueue.main.asyncAfter(deadline: .now() + (0.1 - elapsed), execute: work)
            } else {
                self.progressWorkItem?.cancel()
                self.progressWorkItem = nil
                self.display(current)
            }
        }
    }

    private func display(_ progress: TransferProgress) {
        let sameTransfer = displayedTransfer?.identity == progress.identity
        displayedTransfer = progress
        lastProgressUpdate = ProcessInfo.processInfo.systemUptime
        render(ToastCapsuleView(message: progress.message, icon: progress.icon, progress: progress),
               dismiss: !progress.isActive, animateResize: !sameTransfer)
    }

    private func render(_ toastView: ToastCapsuleView, dismiss: Bool, animateResize: Bool = true) {
        dismissWorkItem?.cancel()
        dismissWorkItem = nil

        let panel: ToastPanel
        if let existingWindow = window, let hosting = hostingView {
            panel = existingWindow
            hosting.rootView = toastView
        } else {
            panel = ToastPanel(
                contentRect: NSRect(x: 0, y: 0, width: 280, height: 70),
                styleMask: [.borderless, .nonactivatingPanel],
                backing: .buffered,
                defer: false
            )
            panel.level = .floating
            panel.isOpaque = false
            panel.backgroundColor = .clear
            panel.hasShadow = false
            panel.ignoresMouseEvents = true
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            panel.isReleasedWhenClosed = false

            let hosting = NSHostingView(rootView: toastView)
            panel.contentView = hosting
            hostingView = hosting
            window = panel
        }

        guard let hosting = hostingView else { return }
        hosting.layoutSubtreeIfNeeded()

        let fittingSize = hosting.fittingSize
        let targetWidth = max(160, min(fittingSize.width, 500))
        let targetHeight = max(58, fittingSize.height)

        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }
        let screenRect = screen.visibleFrame
        let targetFrame = NSRect(x: screenRect.midX - targetWidth / 2,
                                 y: screenRect.maxY - targetHeight - 16,
                                 width: targetWidth, height: targetHeight)
        let isAlreadyVisible = panel.isVisible && panel.alphaValue > 0.05

        if isAlreadyVisible {
            if animateResize {
                NSAnimationContext.runAnimationGroup { context in
                    context.duration = 0.22
                    context.timingFunction = CAMediaTimingFunction(controlPoints: 0.16, 1.0, 0.3, 1.0)
                    panel.animator().setFrame(targetFrame, display: true)
                    panel.animator().alphaValue = 1.0
                }
            } else {
                panel.setFrame(targetFrame, display: true)
                panel.alphaValue = 1.0
            }
        } else {
            panel.setFrame(targetFrame.offsetBy(dx: 0, dy: 10), display: false)
            panel.alphaValue = 0.0
            panel.orderFront(nil)

            NSAnimationContext.runAnimationGroup { context in
                context.duration = 0.28
                context.timingFunction = CAMediaTimingFunction(controlPoints: 0.16, 1.0, 0.3, 1.0)
                panel.animator().setFrame(targetFrame, display: true)
                panel.animator().alphaValue = 1.0
            }
        }

        guard dismiss else { return }
        var workItem: DispatchWorkItem?
        workItem = DispatchWorkItem { [weak self, weak panel] in
            guard let panel else { return }
            let currentFrame = panel.frame
            NSAnimationContext.runAnimationGroup({ context in
                context.duration = 0.22
                context.timingFunction = CAMediaTimingFunction(controlPoints: 0.4, 0.0, 1.0, 1.0)
                panel.animator().setFrame(currentFrame.offsetBy(dx: 0, dy: 8), display: true)
                panel.animator().alphaValue = 0.0
            }, completionHandler: {
                guard let self, let currentItem = self.dismissWorkItem, currentItem === workItem else { return }
                panel.orderOut(nil)
                self.dismissWorkItem = nil
            })
        }
        guard let workItem else { return }
        dismissWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.2, execute: workItem)
    }
}
