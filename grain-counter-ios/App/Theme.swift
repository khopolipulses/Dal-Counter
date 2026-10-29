import UIKit

/// Design tokens and small building blocks shared by every screen: colours, type, glass, buttons, haptics.
enum Theme {
    static let accent = UIColor.systemOrange
    /// Behind photos: always dark, so grains and coloured rings stand out.
    static let canvas = UIColor(red: 0.06, green: 0.06, blue: 0.07, alpha: 1)

    static let markerBlue = UIColor(red: 10 / 255, green: 132 / 255, blue: 1, alpha: 1)
    static let markerOrange = UIColor(red: 1, green: 159 / 255, blue: 10 / 255, alpha: 1)
    static let markerGreen = UIColor(red: 48 / 255, green: 209 / 255, blue: 88 / 255, alpha: 1)

    /// SF Pro Rounded, for numbers and friendly titles.
    static func rounded(_ size: CGFloat, _ weight: UIFont.Weight) -> UIFont {
        let base = UIFont.systemFont(ofSize: size, weight: weight)
        guard let d = base.fontDescriptor.withDesign(.rounded) else { return base }
        return UIFont(descriptor: d, size: size)
    }

    static func symbol(_ name: String, _ size: CGFloat = 17, _ weight: UIImage.SymbolWeight = .semibold) -> UIImage? {
        return UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: weight))
    }

    static func tap(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    static var reduceMotion: Bool { UIAccessibility.isReduceMotionEnabled }

    /// Springy entrance: fades and lifts views in one after another.
    static func reveal(_ views: [UIView], delay: TimeInterval = 0) {
        for (i, v) in views.enumerated() {
            v.alpha = 0
            v.transform = reduceMotion ? .identity : CGAffineTransform(translationX: 0, y: 24).scaledBy(x: 0.98, y: 0.98)
            UIView.animate(withDuration: 0.7, delay: delay + 0.06 * Double(i), usingSpringWithDamping: 0.82,
                           initialSpringVelocity: 0.4, options: [.allowUserInteraction], animations: {
                v.alpha = 1
                v.transform = .identity
            })
        }
    }
}

/// Frosted glass: a blur material with a soft highlight, a hairline edge and a gentle shadow.
class GlassView: UIView {
    let contentView: UIView
    private let blur: UIVisualEffectView
    private let sheen = CAGradientLayer()

    init(cornerRadius: CGFloat = 22, style: UIBlurEffect.Style = .systemUltraThinMaterial) {
        blur = UIVisualEffectView(effect: UIBlurEffect(style: style))
        contentView = blur.contentView
        super.init(frame: .zero)
        layer.cornerRadius = cornerRadius
        layer.cornerCurve = .continuous
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.14
        layer.shadowRadius = 20
        layer.shadowOffset = CGSize(width: 0, height: 10)

        blur.layer.cornerRadius = cornerRadius
        blur.layer.cornerCurve = .continuous
        blur.clipsToBounds = true
        blur.layer.borderWidth = 1 / UIScreen.main.scale * 1.5
        blur.layer.borderColor = UIColor.white.withAlphaComponent(0.28).cgColor
        blur.translatesAutoresizingMaskIntoConstraints = false
        addSubview(blur)
        NSLayoutConstraint.activate([
            blur.topAnchor.constraint(equalTo: topAnchor),
            blur.bottomAnchor.constraint(equalTo: bottomAnchor),
            blur.leadingAnchor.constraint(equalTo: leadingAnchor),
            blur.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
        sheen.colors = [UIColor.white.withAlphaComponent(0.22).cgColor, UIColor.white.withAlphaComponent(0).cgColor]
        sheen.startPoint = CGPoint(x: 0, y: 0)
        sheen.endPoint = CGPoint(x: 0.8, y: 1)
        blur.contentView.layer.insertSublayer(sheen, at: 0)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        sheen.frame = bounds
        layer.shadowPath = UIBezierPath(roundedRect: bounds, cornerRadius: layer.cornerRadius).cgPath
    }

    /// Adds `v` inside the glass with the given padding.
    func embed(_ v: UIView, padding: UIEdgeInsets) {
        v.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(v)
        NSLayoutConstraint.activate([
            v.topAnchor.constraint(equalTo: contentView.topAnchor, constant: padding.top),
            v.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -padding.bottom),
            v.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: padding.left),
            v.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -padding.right),
        ])
    }
}

/// A button that sinks a little under the finger and springs back, with a light tap.
final class PressButton: UIButton {
    var haptic: UIImpactFeedbackGenerator.FeedbackStyle? = .light

    convenience init(_ config: UIButton.Configuration, action: @escaping () -> Void) {
        self.init(configuration: config, primaryAction: nil)
        addAction(UIAction { [weak self] _ in
            if let h = self?.haptic { Theme.tap(h) }
            action()
        }, for: .touchUpInside)
    }

    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: isHighlighted ? 0.12 : 0.45, delay: 0, usingSpringWithDamping: 0.6,
                           initialSpringVelocity: 0.8, options: [.allowUserInteraction, .beginFromCurrentState], animations: {
                self.transform = self.isHighlighted ? CGAffineTransform(scaleX: 0.95, y: 0.95) : .identity
                self.alpha = self.isHighlighted ? 0.85 : 1
            })
        }
    }

    /// Big filled call to action.
    static func filled(_ title: String, symbol: String, action: @escaping () -> Void) -> PressButton {
        var c = UIButton.Configuration.filled()
        c.title = title
        c.image = Theme.symbol(symbol, 18)
        c.imagePadding = 8
        c.baseBackgroundColor = Theme.accent
        c.baseForegroundColor = .white
        c.cornerStyle = .large
        c.buttonSize = .large
        c.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { a in
            var a = a
            a.font = UIFont.systemFont(ofSize: 17, weight: .semibold)
            return a
        }
        return PressButton(c, action: action)
    }

    /// Round glass icon button (back, share...).
    static func round(_ symbol: String, action: @escaping () -> Void) -> PressButton {
        var c = UIButton.Configuration.plain()
        c.image = Theme.symbol(symbol, 17)
        c.baseForegroundColor = .label
        c.background.visualEffect = UIBlurEffect(style: .systemThinMaterial)
        c.background.cornerRadius = 22
        c.cornerStyle = .capsule
        let b = PressButton(c, action: action)
        b.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([b.widthAnchor.constraint(equalToConstant: 44), b.heightAnchor.constraint(equalToConstant: 44)])
        return b
    }

    /// Icon over a small caption, for glass toolbars.
    static func tool(_ title: String, symbol: String, action: @escaping () -> Void) -> PressButton {
        var c = UIButton.Configuration.plain()
        c.image = Theme.symbol(symbol, 19)
        c.title = title
        c.imagePlacement = .top
        c.imagePadding = 4
        c.baseForegroundColor = .label
        c.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 4, bottom: 8, trailing: 4)
        c.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { a in
            var a = a
            a.font = UIFont.systemFont(ofSize: 11, weight: .medium)
            return a
        }
        return PressButton(c, action: action)
    }
}

/// Slowly drifting warm colour blobs behind the content (a soft "mesh gradient").
final class MeshBackgroundView: UIView {
    private let base = CAGradientLayer()
    private var blobs: [CAGradientLayer] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        layer.addSublayer(base)
        for _ in 0..<4 {
            let b = CAGradientLayer()
            b.type = .radial
            b.startPoint = CGPoint(x: 0.5, y: 0.5)
            b.endPoint = CGPoint(x: 1, y: 1)
            layer.addSublayer(b)
            blobs.append(b)
        }
        applyColors()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    private func applyColors() {
        let dark = traitCollection.userInterfaceStyle == .dark
        base.colors = dark
            ? [UIColor(red: 0.10, green: 0.08, blue: 0.07, alpha: 1).cgColor, UIColor(red: 0.04, green: 0.04, blue: 0.05, alpha: 1).cgColor]
            : [UIColor(red: 1.0, green: 0.97, blue: 0.93, alpha: 1).cgColor, UIColor(red: 0.98, green: 0.93, blue: 0.88, alpha: 1).cgColor]
        let tints: [UIColor] = [
            UIColor(red: 1.0, green: 0.62, blue: 0.20, alpha: 1),  // dal orange
            UIColor(red: 1.0, green: 0.80, blue: 0.35, alpha: 1),  // wheat gold
            UIColor(red: 0.96, green: 0.45, blue: 0.40, alpha: 1), // masoor red
            UIColor(red: 0.55, green: 0.75, blue: 0.45, alpha: 1), // moong green
        ]
        for (i, b) in blobs.enumerated() {
            let c = tints[i % tints.count]
            b.colors = [c.withAlphaComponent(dark ? 0.30 : 0.42).cgColor, c.withAlphaComponent(0).cgColor]
        }
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        applyColors()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        base.frame = bounds
        let w = bounds.width, h = bounds.height
        let spots: [(CGFloat, CGFloat, CGFloat)] = [(0.15, 0.12, 1.0), (0.9, 0.3, 0.9), (0.2, 0.75, 0.8), (0.85, 0.9, 1.0)]
        for (i, b) in blobs.enumerated() {
            let (fx, fy, fs) = spots[i]
            let size = max(w, h) * 0.75 * fs
            b.bounds = CGRect(x: 0, y: 0, width: size, height: size)
            b.position = CGPoint(x: w * fx, y: h * fy)
            b.removeAllAnimations()
            if Theme.reduceMotion { continue }
            let a = CABasicAnimation(keyPath: "position")
            a.toValue = CGPoint(x: w * fx + (i % 2 == 0 ? 60 : -50), y: h * fy + (i < 2 ? 70 : -60))
            a.duration = 9 + Double(i) * 2.5
            a.autoreverses = true
            a.repeatCount = .infinity
            a.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            b.add(a, forKey: "drift")
        }
    }
}

/// A short message in a glass capsule that slides in and fades away (like a system HUD).
enum Toast {
    static func show(_ message: String, symbol: String = "checkmark.circle.fill", in view: UIView) {
        let glass = GlassView(cornerRadius: 22, style: .systemThinMaterial)
        let icon = UIImageView(image: Theme.symbol(symbol, 16))
        icon.tintColor = symbol.contains("exclamation") ? Theme.markerOrange : Theme.markerGreen
        let label = UILabel()
        label.text = message
        label.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        label.numberOfLines = 2
        let row = UIStackView(arrangedSubviews: [icon, label])
        row.spacing = 8
        row.alignment = .center
        glass.embed(row, padding: UIEdgeInsets(top: 11, left: 16, bottom: 11, right: 18))
        glass.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(glass)
        NSLayoutConstraint.activate([
            glass.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            glass.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            glass.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -40),
        ])
        glass.alpha = 0
        glass.transform = CGAffineTransform(translationX: 0, y: -30).scaledBy(x: 0.9, y: 0.9)
        UIView.animate(withDuration: 0.55, delay: 0, usingSpringWithDamping: 0.7, initialSpringVelocity: 0.5, options: [], animations: {
            glass.alpha = 1
            glass.transform = .identity
        })
        UIView.animate(withDuration: 0.35, delay: 2.2, options: [.curveEaseIn], animations: {
            glass.alpha = 0
            glass.transform = CGAffineTransform(translationX: 0, y: -20)
        }, completion: { _ in glass.removeFromSuperview() })
    }
}
