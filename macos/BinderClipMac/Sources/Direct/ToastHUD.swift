import AppKit
import SwiftUI

private struct ToastCapsuleView: View {
    let message: String
    let icon: String
    var isAlert = false
    var progress: TransferProgress? = nil
    var fileURL: URL? = nil
    var onClick: (() -> Void)? = nil
    var onClose: (() -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme
    @State private var isHovered = false

    private var isInteractive: Bool {
        onClick != nil || fileURL != nil
    }

    private var iconTint: Color {
        isAlert ? Color(nsColor: .systemRed) : Color(nsColor: .secondaryLabelColor)
    }

    var body: some View {
        HStack(alignment: progress == nil ? .center : .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .regular))
                .foregroundStyle(iconTint)
                .frame(width: 22, height: 22)

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
                    if progress.isActive {
                        if progress.phase == .preparing {
                            ProgressView().progressViewStyle(.linear)
                        } else {
                            ProgressView(value: progress.fraction).progressViewStyle(.linear)
                        }
                        Text(byteCount(progress.completedBytes) + " of " + byteCount(progress.metadata.size))
                            .font(.system(size: 10).monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(width: 280, alignment: .leading)
            } else {
                VStack(alignment: .leading, spacing: isInteractive ? 2 : 0) {
                    Text(message)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if isInteractive {
                        Text(L10n.tr("toast_click_to_reveal"))
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: 340, alignment: .leading)
            }

            if let onClose {
                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 18, height: 18)
                        .background(Circle().fill(Color.primary.opacity(isHovered ? 0.12 : 0.06)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.tr("log_close"))
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, progress == nil ? (isInteractive ? 9 : 8) : 12)
        .liquidGlassBacking(colorScheme: colorScheme, cornerRadius: progress == nil ? 100 : 16)
        .padding(14) // Breathing room for the ambient drop shadow
        .contentShape(Rectangle())
        .onTapGesture {
            onClick?()
        }
        .onHover { hovering in
            isHovered = hovering
            if isInteractive {
                if hovering {
                    NSCursor.pointingHand.push()
                } else {
                    NSCursor.pop()
                }
            }
        }
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
    private var hiddenTransferID: String?

    func show(message: String, icon: String = "checkmark.circle.fill", fileURL: URL? = nil, onClick: (() -> Void)? = nil) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.activeTransfers.isEmpty else { return }
            self.present(message: message, icon: icon, fileURL: fileURL, onClick: onClick)
        }
    }

    /// The finished file owns the toast: clicking it reveals the file in Finder.
    func showFileReceived(url: URL, message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.progressWorkItem?.cancel()
            self.progressWorkItem = nil
            self.activeTransfers.removeAll()
            self.transferOrder.removeAll()
            self.currentTransferID = nil
            self.hiddenTransferID = nil
            self.present(message: message, icon: "checkmark.circle.fill", fileURL: url, onClick: nil)
        }
    }

    private func present(message: String, icon: String, fileURL: URL?, onClick: (() -> Void)?) {
        displayedTransfer = nil
        let clickAction: (() -> Void)?
        if let onClick {
            clickAction = { [weak self] in
                onClick()
                self?.dismissNow()
            }
        } else if let fileURL {
            clickAction = { [weak self] in
                NSWorkspace.shared.activateFileViewerSelecting([fileURL])
                self?.dismissNow()
            }
        } else {
            clickAction = nil
        }
        let isAlert = icon.contains("exclamationmark")
        render(ToastCapsuleView(message: message, icon: icon, isAlert: isAlert, fileURL: fileURL,
                                onClick: clickAction, onClose: { [weak self] in self?.dismissNow() }),
               dismiss: true, interactive: true)
    }

    func dismissNow() {
        dismissWorkItem?.cancel()
        dismissWorkItem = nil
        guard let panel = window, panel.isVisible else { return }
        let currentFrame = panel.frame
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.16
            context.timingFunction = CAMediaTimingFunction(controlPoints: 0.4, 0.0, 1.0, 1.0)
            panel.animator().setFrame(currentFrame.offsetBy(dx: 0, dy: 8), display: true)
            panel.animator().alphaValue = 0.0
        }, completionHandler: { [weak self, weak panel] in
            guard let self, self.dismissWorkItem == nil else { return }
            panel?.orderOut(nil)
        })
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
                // The received-file toast is shown by showFileReceived so it can reveal the file.
                if progress.phase == .received && progress.metadata.kind == "file" { return }
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
        if progress.isActive {
            if hiddenTransferID == progress.identity { return }
        } else if hiddenTransferID == progress.identity {
            hiddenTransferID = nil
        }
        let sameTransfer = displayedTransfer?.identity == progress.identity
        displayedTransfer = progress
        lastProgressUpdate = ProcessInfo.processInfo.systemUptime
        let id = progress.identity
        let close: () -> Void = { [weak self] in
            if progress.isActive { self?.hiddenTransferID = id }
            self?.dismissNow()
        }
        render(ToastCapsuleView(message: progress.message, icon: progress.icon,
                                isAlert: progress.icon.contains("exclamationmark"),
                                progress: progress, onClose: close),
               dismiss: !progress.isActive, animateResize: !sameTransfer, interactive: true)
    }

    private func render(_ toastView: ToastCapsuleView, dismiss: Bool, animateResize: Bool = true, interactive: Bool = false) {
        dismissWorkItem?.cancel()
        dismissWorkItem = nil

        let isInteractive = interactive || toastView.onClick != nil || toastView.fileURL != nil
        let isClickable = toastView.onClick != nil || toastView.fileURL != nil

        let panel: ToastPanel
        if let existingWindow = window, let hosting = hostingView {
            panel = existingWindow
            panel.ignoresMouseEvents = !isInteractive
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
            panel.ignoresMouseEvents = !isInteractive
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
        let delay: TimeInterval = isClickable ? 6 : 3
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
    }
}
