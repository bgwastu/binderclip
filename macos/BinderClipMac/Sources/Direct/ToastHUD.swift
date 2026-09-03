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

            // Message text strictly adhering to dynamic dark/light mode
            Text(message)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: 380, alignment: .leading)
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.vertical, 7)
        .liquidGlassBacking(colorScheme: colorScheme)
        .padding(14) // Breathing room for the ambient drop shadow
    }
}

private struct LiquidGlassModifier: ViewModifier {
    let colorScheme: ColorScheme

    func body(content: Content) -> some View {
        content
            .background(.regularMaterial, in: Capsule())
            .overlay(
                Capsule()
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
    func liquidGlassBacking(colorScheme: ColorScheme) -> some View {
        modifier(LiquidGlassModifier(colorScheme: colorScheme))
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

    func show(message: String, icon: String = "checkmark.circle.fill") {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.dismissWorkItem?.cancel()

            let toastView = ToastCapsuleView(message: message, icon: icon)

            let panel: ToastPanel
            if let existingWindow = self.window, let hosting = self.hostingView {
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
                self.hostingView = hosting
                self.window = panel
            }

            guard let hosting = self.hostingView else { return }
            hosting.layoutSubtreeIfNeeded()

            let fittingSize = hosting.fittingSize
            let targetWidth = max(160, min(fittingSize.width, 500))
            let targetHeight = max(58, fittingSize.height)

            guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }
            let screenRect = screen.visibleFrame
            let targetX = screenRect.midX - (targetWidth / 2)
            let targetY = screenRect.maxY - targetHeight - 16

            let isAlreadyVisible = panel.isVisible && panel.alphaValue > 0.05

            if isAlreadyVisible {
                NSAnimationContext.runAnimationGroup { context in
                    context.duration = 0.22
                    context.timingFunction = CAMediaTimingFunction(controlPoints: 0.16, 1.0, 0.3, 1.0)
                    panel.animator().setFrame(
                        NSRect(x: targetX, y: targetY, width: targetWidth, height: targetHeight),
                        display: true
                    )
                    panel.animator().alphaValue = 1.0
                }
            } else {
                panel.setFrame(
                    NSRect(x: targetX, y: targetY + 10, width: targetWidth, height: targetHeight),
                    display: false
                )
                panel.alphaValue = 0.0
                panel.orderFront(nil)

                NSAnimationContext.runAnimationGroup { context in
                    context.duration = 0.28
                    context.timingFunction = CAMediaTimingFunction(controlPoints: 0.16, 1.0, 0.3, 1.0)
                    panel.animator().setFrame(
                        NSRect(x: targetX, y: targetY, width: targetWidth, height: targetHeight),
                        display: true
                    )
                    panel.animator().alphaValue = 1.0
                }
            }

            var workItem: DispatchWorkItem?
            workItem = DispatchWorkItem { [weak self, weak panel] in
                guard let panel else { return }
                let currentFrame = panel.frame
                NSAnimationContext.runAnimationGroup({ context in
                    context.duration = 0.22
                    context.timingFunction = CAMediaTimingFunction(controlPoints: 0.4, 0.0, 1.0, 1.0)
                    panel.animator().setFrame(
                        NSRect(x: currentFrame.minX, y: currentFrame.minY + 8, width: currentFrame.width, height: currentFrame.height),
                        display: true
                    )
                    panel.animator().alphaValue = 0.0
                }, completionHandler: {
                    guard let self, let currentItem = self.dismissWorkItem, currentItem === workItem else { return }
                    panel.orderOut(nil)
                    self.dismissWorkItem = nil
                })
            }
            guard let workItem else { return }
            self.dismissWorkItem = workItem
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.2, execute: workItem)
        }
    }
}
