import UIKit

/**
 * Shows the photo with numbered markers.
 * Pinch to zoom, drag to pan, tap an empty spot to add a missed grain, tap a marker to remove it.
 * Blue = certain, orange = in a touching/overlapping group (check it), green = added by you.
 */
final class CountView: UIView {

    /// Called with (count, canUndo) whenever the markers change.
    var onMarkersChanged: ((Int, Bool) -> Void)?

    private static let blue = UIColor(red: 0, green: 90 / 255, blue: 1, alpha: 1)
    private static let orange = UIColor(red: 1, green: 130 / 255, blue: 0, alpha: 1)
    private static let green = UIColor(red: 0, green: 170 / 255, blue: 70 / 255, alpha: 1)
    private static let numberRed = UIColor(red: 200 / 255, green: 0, blue: 0, alpha: 1)
    static let background = UIColor(red: 32 / 255, green: 32 / 255, blue: 32 / 255, alpha: 1)

    private var image: UIImage?
    private let markers = Markers()
    private(set) var radius: Float = 12
    private(set) var band: Float = 25

    // Image -> view mapping: viewPoint = imagePoint * scale + offset.
    private var scale: CGFloat = 1
    private var offset = CGPoint.zero
    private var fitScale: CGFloat = 1
    private var lastSize = CGSize.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = CountView.background
        contentMode = .redraw
        isMultipleTouchEnabled = true
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:)))
        let pan = UIPanGestureRecognizer(target: self, action: #selector(pan(_:)))
        pan.maximumNumberOfTouches = 2
        pinch.delegate = self
        pan.delegate = self
        addGestureRecognizer(pinch)
        addGestureRecognizer(pan)
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tap(_:))))
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    /// Shows a freshly counted photo.
    func setData(_ img: UIImage, _ r: DalCounter.Result) {
        image = img
        radius = r.radius
        band = r.band
        markers.set(xs: r.xs, ys: r.ys, uncertain: r.uncertain, band: band)
        resetFit()
        fire()
    }

    var count: Int { markers.count }
    var hasData: Bool { image != nil }

    func undo() {
        if markers.undo() { fire() }
    }

    func resetFit() {
        guard let img = image, bounds.width > 0, bounds.height > 0 else {
            setNeedsDisplay()
            return
        }
        fitScale = min(bounds.width / img.size.width, bounds.height / img.size.height)
        scale = fitScale
        offset = CGPoint(x: (bounds.width - img.size.width * scale) / 2, y: (bounds.height - img.size.height * scale) / 2)
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastSize {
            lastSize = bounds.size
            resetFit()
        }
    }

    private func toImage(_ p: CGPoint) -> CGPoint {
        return CGPoint(x: (p.x - offset.x) / scale, y: (p.y - offset.y) / scale)
    }

    private func toView(_ x: Float, _ y: Float) -> CGPoint {
        return CGPoint(x: CGFloat(x) * scale + offset.x, y: CGFloat(y) * scale + offset.y)
    }

    /// Never lets the photo be dragged completely out of view.
    private func keepOnScreen() {
        guard let img = image else { return }
        let rect = CGRect(x: offset.x, y: offset.y, width: img.size.width * scale, height: img.size.height * scale)
        let keepX = bounds.width * 0.25, keepY = bounds.height * 0.25
        if rect.maxX < keepX { offset.x += keepX - rect.maxX }
        else if rect.minX > bounds.width - keepX { offset.x += bounds.width - keepX - rect.minX }
        if rect.maxY < keepY { offset.y += keepY - rect.maxY }
        else if rect.minY > bounds.height - keepY { offset.y += bounds.height - keepY - rect.minY }
    }

    @objc private func pinch(_ g: UIPinchGestureRecognizer) {
        guard image != nil, g.state == .began || g.state == .changed else { return }
        let target = max(fitScale * 0.9, min(fitScale * 14, scale * g.scale))
        let f = target / scale
        let c = g.location(in: self)
        offset = CGPoint(x: c.x - (c.x - offset.x) * f, y: c.y - (c.y - offset.y) * f)
        scale = target
        g.scale = 1
        keepOnScreen()
        setNeedsDisplay()
    }

    @objc private func pan(_ g: UIPanGestureRecognizer) {
        guard image != nil else { return }
        let t = g.translation(in: self)
        offset.x += t.x
        offset.y += t.y
        g.setTranslation(.zero, in: self)
        keepOnScreen()
        setNeedsDisplay()
    }

    @objc private func tap(_ g: UITapGestureRecognizer) {
        guard let img = image else { return }
        let p = toImage(g.location(in: self))
        // Finger-sized target: at least 8 pt on screen, never smaller than the grain itself.
        let tol = max(radius * 1.2, Float(8 / scale))
        let idx = markers.hit(x: Float(p.x), y: Float(p.y), tolerance: tol)
        if idx >= 0 {
            markers.remove(at: idx)
        } else if p.x >= 0 && p.y >= 0 && p.x < img.size.width && p.y < img.size.height {
            markers.add(x: Float(p.x), y: Float(p.y))
        } else {
            return
        }
        fire()
    }

    private func fire() {
        onMarkersChanged?(markers.count, markers.canUndo)
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let img = image else {
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 16), .foregroundColor: UIColor.lightGray]
            let text = "Pick a photo or take one" as NSString
            let sz = text.size(withAttributes: attrs)
            text.draw(at: CGPoint(x: (bounds.width - sz.width) / 2, y: (bounds.height - sz.height) / 2), withAttributes: attrs)
            return
        }
        img.draw(in: CGRect(x: offset.x, y: offset.y, width: img.size.width * scale, height: img.size.height * scale))

        let rr = CGFloat(radius) * scale * 1.15
        let showNumbers = rr >= 11
        let font = UIFont.boldSystemFont(ofSize: max(1, min(rr * 0.9, 18)))
        let lineWidth = max(1.5, rr * 0.1)
        for i in 0..<markers.count {
            let mk = markers[i]
            let q = toView(mk.x, mk.y)
            if q.x < -rr || q.y < -rr || q.x > bounds.width + rr || q.y > bounds.height + rr { continue }
            let color = CountView.color(mk.kind)
            let ring = UIBezierPath(ovalIn: CGRect(x: q.x - rr, y: q.y - rr, width: 2 * rr, height: 2 * rr))
            ring.lineWidth = lineWidth
            color.setStroke()
            ring.stroke()
            if showNumbers {
                CountView.drawLabel("\(i + 1)", baseline: CGPoint(x: q.x + rr, y: q.y - rr * 0.6), font: font,
                                    color: mk.kind == .auto ? CountView.numberRed : color, outline: font.pointSize * 0.18)
            }
        }
    }

    private static func color(_ kind: Markers.Kind) -> UIColor {
        switch kind {
        case .uncertain: return orange
        case .manual: return green
        case .auto: return blue
        }
    }

    /// Text with a white outline, positioned by its baseline like Android's drawText.
    private static func drawLabel(_ s: String, baseline p: CGPoint, font: UIFont, color: UIColor, outline: CGFloat) {
        let text = s as NSString
        let origin = CGPoint(x: p.x, y: p.y - font.ascender)
        // Stroke width is a percentage of the font size; positive = outline only.
        let strokePct = outline / font.pointSize * 100
        text.draw(at: origin, withAttributes: [.font: font, .strokeColor: UIColor.white, .strokeWidth: strokePct])
        text.draw(at: origin, withAttributes: [.font: font, .foregroundColor: color])
    }

    /// The processed photo with every marker, its number and the total, ready to save or share.
    func renderExport() -> UIImage? {
        guard let img = image else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: img.size, format: format)
        return renderer.image { ctx in
            img.draw(at: .zero)
            let k = max(img.size.width, img.size.height) / 1600
            let rr = CGFloat(radius) * 1.15
            let font = UIFont.boldSystemFont(ofSize: 15 * k)
            for i in 0..<markers.count {
                let mk = markers[i]
                let color = CountView.color(mk.kind)
                let x = CGFloat(mk.x), y = CGFloat(mk.y)
                let ring = UIBezierPath(ovalIn: CGRect(x: x - rr, y: y - rr, width: 2 * rr, height: 2 * rr))
                ring.lineWidth = 2.2 * k
                color.setStroke()
                ring.stroke()
                CountView.drawLabel("\(i + 1)", baseline: CGPoint(x: x + rr, y: y - rr * 0.6), font: font,
                                    color: mk.kind == .auto ? CountView.numberRed : color, outline: 3 * k)
            }

            let box = CGRect(x: 10 * k, y: 10 * k, width: 430 * k, height: 56 * k)
            UIColor.white.setFill()
            ctx.fill(box)
            UIColor.black.setStroke()
            ctx.cgContext.setLineWidth(2 * k)
            ctx.cgContext.stroke(box)
            let title = UIFont.boldSystemFont(ofSize: 40 * k)
            ("Total count: \(markers.count)" as NSString).draw(
                at: CGPoint(x: 20 * k, y: 52 * k - title.ascender),
                withAttributes: [.font: title, .foregroundColor: UIColor.black])
        }
    }
}

extension CountView: UIGestureRecognizerDelegate {
    /// Pinch and two-finger drag work together, like the Android version.
    func gestureRecognizer(_ g: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        return true
    }
}
