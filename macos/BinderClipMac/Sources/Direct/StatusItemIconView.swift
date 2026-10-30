import AppKit
import QuartzCore

final class StatusItemIconView: NSView {
    enum State: Equatable {
        case idle(appearsDisabled: Bool)
        case indeterminate
        case progress(fraction: Double)
    }

    private let imageView = NSImageView()
    private let trackLayer = CAShapeLayer()
    private let spinnerLayer = CAShapeLayer()

    private(set) var state: State = .idle(appearsDisabled: false)

    override init(frame: NSRect) {
        super.init(frame: frame)
        setup()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setup()
    }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    private func setup() {
        wantsLayer = true
        layer?.masksToBounds = false

        let bounds = CGRect(x: 0, y: 0, width: 18, height: 18)
        let center = CGPoint(x: 9, y: 9)
        let radius: CGFloat = 8.0

        let circlePath = CGMutablePath()
        circlePath.addArc(center: center, radius: radius, startAngle: -.pi / 2, endAngle: 1.5 * .pi, clockwise: false)

        trackLayer.frame = bounds
        trackLayer.path = circlePath
        trackLayer.fillColor = nil
        trackLayer.lineWidth = 1.5
        trackLayer.opacity = 0.0
        trackLayer.isHidden = true
        layer?.addSublayer(trackLayer)

        spinnerLayer.frame = bounds
        spinnerLayer.anchorPoint = CGPoint(x: 0.5, y: 0.5)
        spinnerLayer.position = center
        spinnerLayer.path = circlePath
        spinnerLayer.fillColor = nil
        spinnerLayer.lineWidth = 1.5
        spinnerLayer.lineCap = .round
        spinnerLayer.strokeStart = 0.0
        spinnerLayer.strokeEnd = 0.28
        spinnerLayer.opacity = 0.0
        spinnerLayer.isHidden = true
        layer?.addSublayer(spinnerLayer)

        imageView.frame = bounds
        imageView.wantsLayer = true
        imageView.layer?.anchorPoint = CGPoint(x: 0.5, y: 0.5)
        imageView.layer?.position = center
        addSubview(imageView)

        updateColors()
    }

    func setImage(_ image: NSImage?) {
        imageView.image = image
    }

    func updateColors() {
        let isHighlighted = (superview as? NSButton)?.isHighlighted ?? false
        let color = isHighlighted ? NSColor.selectedMenuItemTextColor : NSColor.controlTextColor
        spinnerLayer.strokeColor = color.cgColor
        trackLayer.strokeColor = color.withAlphaComponent(0.2).cgColor
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        updateColors()
    }

    func transition(to newState: State) {
        let previousState = state
        state = newState
        updateColors()

        switch (previousState, newState) {
        case (.idle, .idle(let disabled)):
            imageView.alphaValue = disabled ? 0.45 : 1.0

        case (.idle, .indeterminate):
            animateTransitionIn()
            startSpinning()

        case (.idle, .progress(let fraction)):
            animateTransitionIn()
            updateDeterminate(fraction: fraction)

        case (.indeterminate, .indeterminate):
            break

        case (.indeterminate, .progress(let fraction)):
            spinnerLayer.removeAnimation(forKey: "rotation")
            updateDeterminate(fraction: fraction)

        case (.progress, .progress(let fraction)):
            updateDeterminate(fraction: fraction)

        case (.progress, .indeterminate):
            startSpinning()

        case (.indeterminate, .idle(let disabled)):
            animateTransitionOut(disabled: disabled)

        case (.progress, .idle(let disabled)):
            animateTransitionOut(disabled: disabled)
        }
    }

    private func animateTransitionIn() {
        imageView.alphaValue = 1.0
        trackLayer.isHidden = false
        spinnerLayer.isHidden = false

        let spring = CASpringAnimation(keyPath: "transform.scale")
        spring.fromValue = 1.0
        spring.toValue = 0.52
        spring.damping = 15
        spring.stiffness = 160
        spring.mass = 0.8
        spring.duration = spring.settlingDuration
        imageView.layer?.transform = CATransform3DMakeScale(0.52, 0.52, 1.0)
        imageView.layer?.add(spring, forKey: "scaleDown")

        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0.0
        fade.toValue = 1.0
        fade.duration = 0.22
        trackLayer.opacity = 1.0
        trackLayer.add(fade, forKey: "fadeIn")
        spinnerLayer.opacity = 1.0
        spinnerLayer.add(fade, forKey: "fadeIn")
    }

    private func animateTransitionOut(disabled: Bool) {
        imageView.alphaValue = disabled ? 0.45 : 1.0

        CATransaction.begin()
        CATransaction.setAnimationDuration(0.25)
        CATransaction.setCompletionBlock { [weak self] in
            guard let self else { return }
            if case .idle = self.state {
                self.spinnerLayer.removeAnimation(forKey: "rotation")
                self.spinnerLayer.isHidden = true
                self.trackLayer.isHidden = true
            }
        }

        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 1.0
        fade.toValue = 0.0
        fade.duration = 0.25
        trackLayer.opacity = 0.0
        trackLayer.add(fade, forKey: "fadeOut")
        spinnerLayer.opacity = 0.0
        spinnerLayer.add(fade, forKey: "fadeOut")

        CATransaction.commit()

        let spring = CASpringAnimation(keyPath: "transform.scale")
        spring.fromValue = 0.52
        spring.toValue = 1.0
        spring.damping = 12
        spring.stiffness = 180
        spring.mass = 0.7
        spring.duration = spring.settlingDuration
        imageView.layer?.transform = CATransform3DIdentity
        imageView.layer?.add(spring, forKey: "scaleUp")
    }

    private func startSpinning() {
        spinnerLayer.strokeStart = 0.0
        spinnerLayer.strokeEnd = 0.28
        guard spinnerLayer.animation(forKey: "rotation") == nil else { return }
        let spin = CABasicAnimation(keyPath: "transform.rotation.z")
        spin.fromValue = 0.0
        spin.toValue = -2.0 * Double.pi
        spin.duration = 0.95
        spin.repeatCount = .infinity
        spin.isRemovedOnCompletion = false
        spinnerLayer.add(spin, forKey: "rotation")
    }

    private func updateDeterminate(fraction: Double) {
        spinnerLayer.removeAnimation(forKey: "rotation")
        spinnerLayer.strokeStart = 0.0
        let target = max(0.04, min(1.0, CGFloat(fraction)))
        CATransaction.begin()
        CATransaction.setAnimationDuration(0.12)
        spinnerLayer.strokeEnd = target
        CATransaction.commit()
    }

    func resetToIdle(disabled: Bool = false) {
        state = .idle(appearsDisabled: disabled)
        imageView.layer?.removeAllAnimations()
        imageView.layer?.transform = CATransform3DIdentity
        imageView.alphaValue = disabled ? 0.45 : 1.0
        spinnerLayer.removeAllAnimations()
        spinnerLayer.opacity = 0.0
        spinnerLayer.isHidden = true
        trackLayer.removeAllAnimations()
        trackLayer.opacity = 0.0
        trackLayer.isHidden = true
    }

    func pulseSync() {
        guard case .idle = state else { return }
        let disabled = imageView.alphaValue < 0.6
        transition(to: .indeterminate)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.65) { [weak self] in
            guard let self, case .indeterminate = self.state else { return }
            self.transition(to: .idle(appearsDisabled: disabled))
        }
    }
}
