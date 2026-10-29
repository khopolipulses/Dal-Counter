import UIKit

/**
 * Shows the photo with numbered markers.
 * Pinch to zoom, drag to pan, tap an empty spot to add a missed grain, tap a marker to remove it.
 * Blue = counted, orange = worth a check, green = added by you. Markers pop in, the view can glide to one marker
 * (review), and a light sweep runs over the photo while it is being counted.
 */
final class CountView: UIView {

    /// Called whenever the markers change (add, remove, confirm, undo, new photo).
    var onChange: (() -> Void)?
    /// Space covered by floating bars: the photo is fitted inside the rest.
    var fitInsets: UIEdgeInsets = .zero

    let markers = Markers()
    private(set) var image: UIImage?
    private(set) var radius: Float = 12
    private(set) var band: Float = 25
    private(set) var panGesture: UIPanGestureRecognizer!

    /// Marker drawn with a pulsing halo (the one being reviewed).
    var highlighted: Markers.Marker? {
        didSet { updateLink(); setNeedsDisplay() }
    }

    /// Light sweep over the photo while counting.
    var scanning = false {
        didSet { updateLink(); setNeedsDisplay() }
    }

    // Image -> view mapping: viewPoint = imagePoint * scale + offset.
    private var scale: CGFloat = 1
    private var offset = CGPoint.zero
    private var fitScale: CGFloat = 1
    private var lastSize = CGSize.zero

    private var link: CADisplayLink?
    private var appearStart: CFTimeInterval = 0
    private var glide: (fromScale: CGFloat, fromOffset: CGPoint, toScale: CGFloat, toOffset: CGPoint, start: CFTimeInterval)?
    private static let glideTime = 0.5

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Theme.canvas
        contentMode = .redraw
        isMultipleTouchEnabled = true
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:)))
        let pan = UIPanGestureRecognizer(target: self, action: #selector(pan(_:)))
        pan.maximumNumberOfTouches = 2
        pinch.delegate = self
        pan.delegate = self
        addGestureRecognizer(pinch)
        addGestureRecognizer(pan)
        panGesture = pan
        let single = UITapGestureRecognizer(target: self, action: #selector(tap(_:)))
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTap(_:)))
        double.numberOfTapsRequired = 2
        single.require(toFail: double)   // double tap zooms; it must not also add a grain
        addGestureRecognizer(single)
        addGestureRecognizer(double)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    deinit {
        link?.invalidate()
    }

    // ------------------------------------------------------------------ data

    /// Shows the photo alone (while it is being counted).
    func showPhoto(_ img: UIImage) {
        image = img
        markers.set(xs: [], ys: [], uncertain: nil, band: 25)
        resetFit(animated: false)
    }

    /// Shows a counted photo; the markers pop in one after another.
    func setData(_ img: UIImage, _ r: DalCounter.Result) {
        image = img
        radius = r.radius
        band = r.band
        markers.set(xs: r.xs, ys: r.ys, uncertain: r.uncertain, band: band)
        if bounds.size != lastSize || lastSize == .zero { resetFit(animated: false) }
        appearStart = Theme.reduceMotion ? 0 : CACurrentMediaTime()
        updateLink()
        changed()
    }

    var hasData: Bool { image != nil }

    func undo() {
        if markers.undo() { changed() }
    }

    func confirm(_ m: Markers.Marker) {
        guard let i = markers.index(of: m) else { return }
        markers.confirm(at: i)
        changed()
    }

    func remove(_ m: Markers.Marker) {
        guard let i = markers.index(of: m) else { return }
        markers.remove(at: i)
        changed()
    }

    private func changed() {
        onChange?()
        setNeedsDisplay()
    }

    // ------------------------------------------------------------------ fitting and gliding

    private var available: CGRect { bounds.inset(by: fitInsets) }

    private func fitTarget() -> (CGFloat, CGPoint) {
        guard let img = image, available.width > 0, available.height > 0 else { return (scale, offset) }
        let s = min(available.width / img.size.width, available.height / img.size.height)
        let o = CGPoint(x: available.midX - img.size.width * s / 2, y: available.midY - img.size.height * s / 2)
        return (s, o)
    }

    func resetFit(animated: Bool) {
        guard image != nil, bounds.width > 0 else {
            setNeedsDisplay()
            return
        }
        let (s, o) = fitTarget()
        fitScale = s
        if animated { glideTo(s, o) } else {
            glide = nil
            scale = s
            offset = o
            setNeedsDisplay()
        }
    }

    /// Glides so that this marker sits in the middle of the free area, big enough to judge.
    func focus(on m: Markers.Marker) {
        let target = min(fitScale * 14, max(fitScale * 1.5, 46 / (CGFloat(radius) * 1.15)))
        let c = CGPoint(x: available.midX, y: available.midY)
        glideTo(target, CGPoint(x: c.x - CGFloat(m.x) * target, y: c.y - CGFloat(m.y) * target))
    }

    private func glideTo(_ s: CGFloat, _ o: CGPoint) {
        if Theme.reduceMotion {
            scale = s
            offset = o
            setNeedsDisplay()
            return
        }
        glide = (scale, offset, s, o, CACurrentMediaTime())
        updateLink()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastSize {
            lastSize = bounds.size
            resetFit(animated: false)
        }
    }

    // ------------------------------------------------------------------ animation clock

    private var animating: Bool {
        return appearStart > 0 || glide != nil || highlighted != nil || scanning
    }

    private func updateLink() {
        if animating && link == nil {
            let l = CADisplayLink(target: self, selector: #selector(tick))
            l.add(to: .main, forMode: .common)
            link = l
        } else if !animating, let l = link {
            l.invalidate()
            link = nil
        }
    }

    @objc private func tick() {
        let now = CACurrentMediaTime()
        if let g = glide {
            let p = min(1, (now - g.start) / CountView.glideTime)
            let e = CGFloat(p < 0.5 ? 4 * p * p * p : 1 - pow(-2 * p + 2, 3) / 2) // ease in-out
            scale = g.fromScale + (g.toScale - g.fromScale) * e
            offset = CGPoint(x: g.fromOffset.x + (g.toOffset.x - g.fromOffset.x) * e,
                             y: g.fromOffset.y + (g.toOffset.y - g.fromOffset.y) * e)
            if p >= 1 { glide = nil }
        }
        if appearStart > 0 && now - appearStart > 1.0 { appearStart = 0 }
        setNeedsDisplay()
        updateLink()
    }

    // ------------------------------------------------------------------ gestures

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
        glide = nil
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
        glide = nil
        let t = g.translation(in: self)
        offset.x += t.x
        offset.y += t.y
        g.setTranslation(.zero, in: self)
        keepOnScreen()
        setNeedsDisplay()
    }

    @objc private func doubleTap(_ g: UITapGestureRecognizer) {
        guard image != nil else { return }
        if scale > fitScale * 1.2 {
            resetFit(animated: true)
        } else {
            let p = g.location(in: self), s = fitScale * 3
            let ip = toImage(p)
            glideTo(s, CGPoint(x: p.x - ip.x * s, y: p.y - ip.y * s))
        }
    }

    @objc private func tap(_ g: UITapGestureRecognizer) {
        guard let img = image, !scanning else { return }
        let p = toImage(g.location(in: self))
        // Finger-sized target: at least 8 pt on screen, never smaller than the grain itself.
        let tol = max(radius * 1.2, Float(8 / scale))
        let idx = markers.hit(x: Float(p.x), y: Float(p.y), tolerance: tol)
        if idx >= 0 {
            markers.remove(at: idx)
            Theme.tap(.medium)
        } else if p.x >= 0 && p.y >= 0 && p.x < img.size.width && p.y < img.size.height {
            markers.add(x: Float(p.x), y: Float(p.y))
            Theme.tap(.light)
        } else {
            return
        }
        changed()
    }

    // ------------------------------------------------------------------ drawing

    static func color(_ kind: Markers.Kind) -> UIColor {
        switch kind {
        case .uncertain: return Theme.markerOrange
        case .manual: return Theme.markerGreen
        case .auto, .confirmed: return Theme.markerBlue
        }
    }

    override func draw(_ rect: CGRect) {
        guard let img = image, let ctx = UIGraphicsGetCurrentContext() else { return }
        let photo = CGRect(x: offset.x, y: offset.y, width: img.size.width * scale, height: img.size.height * scale)
        img.draw(in: photo)
        let now = CACurrentMediaTime()

        if scanning {
            ctx.saveGState()
            ctx.clip(to: photo)
            UIColor.black.withAlphaComponent(0.25).setFill()
            ctx.fill(photo)
            let period = 1.8, p = CGFloat((now.truncatingRemainder(dividingBy: period)) / period)
            let y = photo.minY - 60 + (photo.height + 120) * p
            let colors = [UIColor.white.withAlphaComponent(0).cgColor, UIColor.white.withAlphaComponent(0.45).cgColor,
                          UIColor.white.withAlphaComponent(0).cgColor] as CFArray
            if let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 0.5, 1]) {
                ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: y - 60), end: CGPoint(x: 0, y: y + 60), options: [])
            }
            ctx.restoreGState()
            return
        }

        let rr = CGFloat(radius) * scale * 1.15
        let showNumbers = rr >= 11
        let font = UIFont.systemFont(ofSize: max(1, min(rr * 0.9, 17)), weight: .bold)
        let lineWidth = max(1.6, rr * 0.11)
        let n = markers.count
        for i in 0..<n {
            let mk = markers[i]
            let q = toView(mk.x, mk.y)
            if q.x < -rr * 2 || q.y < -rr * 2 || q.x > bounds.width + rr * 2 || q.y > bounds.height + rr * 2 { continue }
            var grow: CGFloat = 1, alpha: CGFloat = 1
            if appearStart > 0 {                                  // staggered pop-in
                let delay = 0.55 * Double(i) / Double(max(1, n))
                let p = CGFloat(max(0, min(1, (now - appearStart - delay) / 0.3)))
                let c1: CGFloat = 1.70158, c3 = c1 + 1
                grow = p <= 0 ? 0 : 1 + c3 * pow(p - 1, 3) + c1 * pow(p - 1, 2) // ease out back
                alpha = p
            }
            if grow <= 0 { continue }
            let color = CountView.color(mk.kind)
            let r = rr * grow
            let ring = UIBezierPath(ovalIn: CGRect(x: q.x - r, y: q.y - r, width: 2 * r, height: 2 * r))
            ring.lineWidth = lineWidth
            color.withAlphaComponent(alpha).setStroke()
            ring.stroke()
            if mk.kind == .confirmed && rr >= 8 {               // small tick: checked by you
                let t = UIBezierPath()
                let k = rr * 0.35
                t.move(to: CGPoint(x: q.x - k, y: q.y))
                t.addLine(to: CGPoint(x: q.x - k * 0.25, y: q.y + k * 0.7))
                t.addLine(to: CGPoint(x: q.x + k, y: q.y - k * 0.7))
                t.lineWidth = lineWidth
                t.lineCapStyle = .round
                t.lineJoinStyle = .round
                color.setStroke()
                t.stroke()
            }
            if showNumbers && alpha > 0.5 {
                CountView.drawLabel("\(i + 1)", baseline: CGPoint(x: q.x + rr, y: q.y - rr * 0.6), font: font,
                                    color: color, outline: font.pointSize * 0.18)
            }
            if mk === highlighted {                               // review halo
                let pulse = CGFloat(0.5 + 0.5 * sin(now * 5))
                let hr = rr * (1.45 + 0.15 * pulse)
                let halo = UIBezierPath(ovalIn: CGRect(x: q.x - hr, y: q.y - hr, width: 2 * hr, height: 2 * hr))
                halo.lineWidth = max(3, rr * 0.18)
                UIColor.white.withAlphaComponent(0.55 + 0.4 * pulse).setStroke()
                halo.stroke()
            }
        }
    }

    /// Text with a white outline, positioned by its baseline.
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
            let font = UIFont.systemFont(ofSize: 15 * k, weight: .bold)
            for i in 0..<markers.count {
                let mk = markers[i]
                let color = CountView.color(mk.kind)
                let x = CGFloat(mk.x), y = CGFloat(mk.y)
                let ring = UIBezierPath(ovalIn: CGRect(x: x - rr, y: y - rr, width: 2 * rr, height: 2 * rr))
                ring.lineWidth = 2.2 * k
                color.setStroke()
                ring.stroke()
                CountView.drawLabel("\(i + 1)", baseline: CGPoint(x: x + rr, y: y - rr * 0.6), font: font, color: color, outline: 3 * k)
            }
            let title = Theme.rounded(40 * k, .bold)
            let text = "Total count: \(markers.count)" as NSString
            let tw = text.size(withAttributes: [.font: title]).width
            let box = CGRect(x: 12 * k, y: 12 * k, width: tw + 32 * k, height: 60 * k)
            UIColor.white.withAlphaComponent(0.92).setFill()
            UIBezierPath(roundedRect: box, cornerRadius: 16 * k).fill()
            text.draw(at: CGPoint(x: box.minX + 16 * k, y: box.midY - title.lineHeight / 2),
                      withAttributes: [.font: title, .foregroundColor: UIColor.black])
            _ = ctx
        }
    }
}

extension CountView: UIGestureRecognizerDelegate {
    /// Pinch and two-finger drag work together.
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        return g.view === other.view
    }
}
