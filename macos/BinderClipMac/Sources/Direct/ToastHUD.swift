import AppKit
import SwiftUI

private struct ToastCapsuleView: View {
    let message: String
    let icon: String
    let onClose: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 16))
                .foregroundStyle(Color(nsColor: icon.contains("exclamationmark") ? .systemRed : .secondaryLabelColor))
                .frame(width: 22, height: 22)
            Text(message)
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
                .frame(maxWidth: 340, alignment: .leading)
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 18, height: 18)
                    .background(Circle().fill(Color.primary.opacity(0.08)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.tr("log_close"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .liquidGlassBacking(colorScheme: colorScheme, cornerRadius: 100)
        .padding(14)
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
    private var panel: ToastPanel?
    private var hosting: NSHostingView<ToastCapsuleView>?
    private var dismissal: DispatchWorkItem?
    private var revision = 0

    func show(message: String, icon: String = "checkmark.circle.fill") {
        DispatchQueue.main.async { [weak self] in self?.present(message, icon: icon) }
    }

    private func present(_ message: String, icon: String) {
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }
        dismissal?.cancel()
        revision += 1
        let view = ToastCapsuleView(message: message, icon: icon, onClose: { [weak self] in self?.dismiss() })
        let window: ToastPanel
        let content: NSHostingView<ToastCapsuleView>
        if let panel, let hosting {
            window = panel
            content = hosting
            content.rootView = view
        } else {
            window = ToastPanel(contentRect: NSRect(x: 0, y: 0, width: 280, height: 70),
                                styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            window.level = .floating
            window.isOpaque = false
            window.backgroundColor = .clear
            window.hasShadow = false
            window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            window.isReleasedWhenClosed = false
            content = NSHostingView(rootView: view)
            window.contentView = content
            panel = window
            hosting = content
        }

        content.layoutSubtreeIfNeeded()
        let size = content.fittingSize
        let width = max(160, min(size.width, 500))
        let height = max(58, size.height)
        let visible = screen.visibleFrame
        let frame = NSRect(x: visible.midX - width / 2, y: visible.maxY - height - 16, width: width, height: height)
        if !window.isVisible {
            window.setFrame(frame.offsetBy(dx: 0, dy: 10), display: false)
            window.alphaValue = 0
            window.orderFront(nil)
        }
        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.22
            window.animator().setFrame(frame, display: true)
            window.animator().alphaValue = 1
        }
        let work = DispatchWorkItem { [weak self] in self?.dismiss() }
        dismissal = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    private func dismiss() {
        dismissal?.cancel()
        dismissal = nil
        guard let panel, panel.isVisible else { return }
        let current = revision
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.16
            panel.animator().setFrame(panel.frame.offsetBy(dx: 0, dy: 8), display: true)
            panel.animator().alphaValue = 0
        }, completionHandler: { [weak self, weak panel] in
            if self?.revision == current { panel?.orderOut(nil) }
        })
    }
}
