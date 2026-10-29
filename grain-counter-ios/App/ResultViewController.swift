import Photos
import UIKit

/**
 * Counts one photo and lets the user make the count exact: the photo fills the screen under floating glass
 * controls, every doubtful grain is orange, and Review walks through them one by one (keep or remove).
 * The finished count is kept in History when the screen is left.
 */
final class ResultViewController: UIViewController {

    /// Delivers the upright photo (on any thread), or nil if it could not be read.
    typealias Source = (@escaping (CGImage?) -> Void) -> Void

    private let source: Source
    private let countView = CountView()
    private var result: DalCounter.Result?
    private let historyID = UUID().uuidString
    private var savedToPhotos = false
    private var reviewedSome = false

    // top
    private var backButton: PressButton!
    private var shareButton: PressButton!
    private let topCard = GlassView(cornerRadius: 26, style: .systemThinMaterialDark)
    private let countLabel = UILabel()
    private let unitLabel = UILabel()
    private var statusPill: PressButton!
    private let tipBanner = GlassView(cornerRadius: 18, style: .systemThinMaterialDark)
    private let tipLabel = UILabel()
    // bottom
    private let toolbar = GlassView(cornerRadius: 30, style: .systemThinMaterialDark)
    private var undoButton: PressButton!
    private var reviewButton: PressButton!
    private let reviewCard = GlassView(cornerRadius: 28, style: .systemThinMaterialDark)
    private let reviewTitle = UILabel()
    private let reviewSubtitle = UILabel()
    private let reviewProgress = UIProgressView(progressViewStyle: .default)
    private var reviewing: Markers.Marker?
    private var reviewTotal = 0
    // counting
    private let countingPill = GlassView(cornerRadius: 24, style: .systemThinMaterialDark)

    private var lastInsets = UIEdgeInsets.zero
    private var countTimer: CADisplayLink?
    private var countFrom = 0, countTo = 0
    private var countStart: CFTimeInterval = 0

    init(source: @escaping Source) {
        self.source = source
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        overrideUserInterfaceStyle = .dark
        view.backgroundColor = Theme.canvas
        buildUi()
        startCounting()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Keep the edge swipe back working with the bar hidden; it wins over panning the photo at the edge.
        if let pop = navigationController?.interactivePopGestureRecognizer {
            pop.delegate = nil
            countView.panGesture.require(toFail: pop)
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if isMovingFromParent { keepInHistory() }
    }

    // ------------------------------------------------------------------ layout

    private func buildUi() {
        countView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(countView)
        countView.onChange = { [weak self] in self?.markersChanged() }

        backButton = PressButton.round("chevron.backward") { [weak self] in
            self?.navigationController?.popViewController(animated: true)
        }
        shareButton = PressButton.round("square.and.arrow.up") { [weak self] in self?.share() }

        countLabel.font = Theme.rounded(44, .bold)
        countLabel.textColor = .white
        countLabel.textAlignment = .center
        countLabel.text = "–"
        unitLabel.font = UIFont.systemFont(ofSize: 13, weight: .medium)
        unitLabel.textColor = UIColor.white.withAlphaComponent(0.7)
        unitLabel.textAlignment = .center
        unitLabel.text = "Counting…"
        var pc = UIButton.Configuration.filled()
        pc.cornerStyle = .capsule
        pc.buttonSize = .small
        pc.imagePadding = 5
        statusPill = PressButton(pc) { [weak self] in self?.statusTapped() }
        statusPill.isHidden = true
        let topStack = UIStackView(arrangedSubviews: [countLabel, unitLabel, statusPill])
        topStack.axis = .vertical
        topStack.alignment = .center
        topStack.spacing = 2
        topStack.setCustomSpacing(8, after: unitLabel)
        topCard.embed(topStack, padding: UIEdgeInsets(top: 10, left: 22, bottom: 12, right: 22))

        let tipIcon = UIImageView(image: Theme.symbol("lightbulb.fill", 15))
        tipIcon.tintColor = UIColor.systemYellow
        tipIcon.setContentHuggingPriority(.required, for: .horizontal)
        tipLabel.font = UIFont.systemFont(ofSize: 13, weight: .medium)
        tipLabel.textColor = .white
        tipLabel.numberOfLines = 0
        let close = PressButton.round("xmark") { [weak self] in self?.setTip(visible: false) }
        close.transform = CGAffineTransform(scaleX: 0.75, y: 0.75)
        let tipRow = UIStackView(arrangedSubviews: [tipIcon, tipLabel, close])
        tipRow.spacing = 10
        tipRow.alignment = .center
        tipBanner.embed(tipRow, padding: UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 6))
        tipBanner.alpha = 0

        undoButton = PressButton.tool("Undo", symbol: "arrow.uturn.backward") { [weak self] in self?.countView.undo() }
        reviewButton = PressButton.tool("Review", symbol: "checklist") { [weak self] in self?.startReview() }
        let fit = PressButton.tool("Fit", symbol: "arrow.up.left.and.arrow.down.right") { [weak self] in
            self?.countView.resetFit(animated: true)
        }
        let save = PressButton.tool("Save", symbol: "square.and.arrow.down") { [weak self] in self?.saveToPhotos() }
        let tools = UIStackView(arrangedSubviews: [undoButton, reviewButton, fit, save])
        tools.distribution = .fillEqually
        toolbar.embed(tools, padding: UIEdgeInsets(top: 4, left: 8, bottom: 4, right: 8))
        toolbar.alpha = 0

        buildReviewCard()

        let spinner = UIActivityIndicatorView(style: .medium)
        spinner.color = .white
        spinner.startAnimating()
        let countingText = UILabel()
        countingText.text = "Counting grains…"
        countingText.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        countingText.textColor = .white
        let countingRow = UIStackView(arrangedSubviews: [spinner, countingText])
        countingRow.spacing = 10
        countingPill.embed(countingRow, padding: UIEdgeInsets(top: 13, left: 18, bottom: 13, right: 20))

        for v in [backButton!, shareButton!, topCard, tipBanner, toolbar, reviewCard, countingPill] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            countView.topAnchor.constraint(equalTo: view.topAnchor),
            countView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            countView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            countView.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            backButton.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16),
            backButton.topAnchor.constraint(equalTo: g.topAnchor, constant: 8),
            shareButton.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -16),
            shareButton.topAnchor.constraint(equalTo: g.topAnchor, constant: 8),

            topCard.centerXAnchor.constraint(equalTo: g.centerXAnchor),
            topCard.topAnchor.constraint(equalTo: g.topAnchor, constant: 4),
            topCard.leadingAnchor.constraint(greaterThanOrEqualTo: backButton.trailingAnchor, constant: 10),
            topCard.trailingAnchor.constraint(lessThanOrEqualTo: shareButton.leadingAnchor, constant: -10),
            topCard.widthAnchor.constraint(greaterThanOrEqualToConstant: 150),

            tipBanner.topAnchor.constraint(equalTo: topCard.bottomAnchor, constant: 10),
            tipBanner.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16),
            tipBanner.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -16),

            toolbar.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16),
            toolbar.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -16),
            toolbar.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -8),

            reviewCard.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 12),
            reviewCard.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -12),
            reviewCard.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -8),

            countingPill.centerXAnchor.constraint(equalTo: g.centerXAnchor),
            countingPill.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -24),
        ])
    }

    private func buildReviewCard() {
        reviewTitle.font = Theme.rounded(20, .bold)
        reviewTitle.textColor = .white
        reviewSubtitle.font = UIFont.systemFont(ofSize: 14, weight: .regular)
        reviewSubtitle.textColor = UIColor.white.withAlphaComponent(0.75)
        reviewSubtitle.numberOfLines = 0
        reviewProgress.progressTintColor = Theme.accent
        reviewProgress.trackTintColor = UIColor.white.withAlphaComponent(0.15)

        var done = UIButton.Configuration.plain()
        done.title = "Done"
        done.baseForegroundColor = Theme.accent
        let doneButton = PressButton(done) { [weak self] in self?.endReview() }
        doneButton.setContentHuggingPriority(.required, for: .horizontal)
        let header = UIStackView(arrangedSubviews: [reviewTitle, doneButton])
        header.alignment = .center

        var remove = UIButton.Configuration.filled()
        remove.title = "Not a grain"
        remove.image = Theme.symbol("trash", 15)
        remove.imagePadding = 6
        remove.cornerStyle = .large
        remove.baseBackgroundColor = UIColor.white.withAlphaComponent(0.14)
        remove.baseForegroundColor = UIColor.systemRed
        let removeButton = PressButton(remove) { [weak self] in self?.reviewAnswer(keep: false) }
        removeButton.haptic = .medium

        var keep = UIButton.Configuration.filled()
        keep.title = "Yes, a grain"
        keep.image = Theme.symbol("checkmark", 15)
        keep.imagePadding = 6
        keep.cornerStyle = .large
        keep.baseBackgroundColor = Theme.accent
        keep.baseForegroundColor = .white
        let keepButton = PressButton(keep) { [weak self] in self?.reviewAnswer(keep: true) }

        let answers = UIStackView(arrangedSubviews: [removeButton, keepButton])
        answers.spacing = 10
        answers.distribution = .fillEqually

        var skip = UIButton.Configuration.plain()
        skip.title = "Skip for now"
        skip.baseForegroundColor = UIColor.white.withAlphaComponent(0.7)
        skip.buttonSize = .small
        let skipButton = PressButton(skip) { [weak self] in self?.reviewSkip() }

        let stack = UIStackView(arrangedSubviews: [header, reviewSubtitle, reviewProgress, answers, skipButton])
        stack.axis = .vertical
        stack.spacing = 10
        stack.setCustomSpacing(14, after: reviewProgress)
        reviewCard.embed(stack, padding: UIEdgeInsets(top: 14, left: 18, bottom: 8, right: 10))
        reviewCard.alpha = 0
        reviewCard.isHidden = true
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // The photo fits between the floating card at the top and the toolbar at the bottom.
        let insets = UIEdgeInsets(top: topCard.frame.maxY + 8, left: 8,
                                  bottom: view.bounds.height - toolbar.frame.minY + 8, right: 8)
        if insets != lastInsets {
            lastInsets = insets
            countView.fitInsets = insets
            if reviewing == nil { countView.resetFit(animated: false) }
        }
    }

    // ------------------------------------------------------------------ counting

    private func startCounting() {
        let started = CACurrentMediaTime()
        source { [weak self] cg in
            DispatchQueue.global(qos: .userInitiated).async {
                let px = cg.flatMap { ImageLoader.pixels($0) }
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    guard let px = px else {
                        self.failed()
                        return
                    }
                    self.countView.showPhoto(px.image)
                    self.countView.scanning = true
                    DispatchQueue.global(qos: .userInitiated).async {
                        let res = DalCounter.count(argb: px.argb, width: px.w, height: px.h)
                        // Let the sweep be seen for a moment, so the result does not just blink in.
                        let wait = max(0, 0.9 - (CACurrentMediaTime() - started))
                        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { self.show(px.image, res) }
                    }
                }
            }
        }
    }

    private func failed() {
        countingPill.isHidden = true
        countLabel.text = "–"
        unitLabel.text = "Could not read that photo"
        Toast.show("Could not read that photo", symbol: "exclamationmark.triangle.fill", in: view)
    }

    private func show(_ image: UIImage, _ res: DalCounter.Result) {
        result = res
        countView.scanning = false
        countView.setData(image, res)
        UIView.animate(withDuration: 0.3) { self.countingPill.alpha = 0 }
        Theme.reveal([toolbar], delay: 0.15)
        statusPill.isHidden = false
        animateCount(to: res.count)
        if let tip = res.photoTip {
            tipLabel.text = tip
            setTip(visible: true)
        }
        if res.count > 0 { Theme.success() }
    }

    /// The big number counts up to the result.
    private func animateCount(to n: Int) {
        countFrom = 0
        countTo = n
        countStart = CACurrentMediaTime()
        countTimer?.invalidate()
        if Theme.reduceMotion || n == 0 {
            countLabel.text = "\(n)"
            return
        }
        let l = CADisplayLink(target: self, selector: #selector(countTick))
        l.add(to: .main, forMode: .common)
        countTimer = l
    }

    @objc private func countTick() {
        let p = min(1, (CACurrentMediaTime() - countStart) / 0.8)
        let e = 1 - pow(1 - p, 3)
        countLabel.text = "\(countFrom + Int((Double(countTo - countFrom) * e).rounded()))"
        if p >= 1 {
            countTimer?.invalidate()
            countTimer = nil
            countLabel.text = "\(countView.markers.count)"
        }
    }

    // ------------------------------------------------------------------ status

    private func markersChanged() {
        guard result != nil else { return }
        savedToPhotos = false
        if countTimer == nil {
            countLabel.text = "\(countView.markers.count)"
            UIView.animate(withDuration: 0.12, animations: {
                self.countLabel.transform = CGAffineTransform(scaleX: 1.12, y: 1.12)
            }, completion: { _ in
                UIView.animate(withDuration: 0.4, delay: 0, usingSpringWithDamping: 0.5, initialSpringVelocity: 0.5,
                               options: [], animations: { self.countLabel.transform = .identity })
            })
        }
        updateStatus()
    }

    private func updateStatus() {
        guard let res = result else { return }
        let m = countView.markers
        let n = m.count, toCheck = m.count(of: .uncertain)
        var unit = n == 1 ? "grain" : "grains"
        if res.ignoredCount > 0 { unit += " · \(res.ignoredCount) print marks ignored" }
        unitLabel.text = unit
        undoButton.isEnabled = m.canUndo

        var rc = reviewButton.configuration
        rc?.title = toCheck > 0 ? "Review \(toCheck)" : "Review"
        reviewButton.configuration = rc
        reviewButton.isEnabled = toCheck > 0

        var c = statusPill.configuration ?? UIButton.Configuration.filled()
        var text = "", symbol = "", color = UIColor.systemGray
        if n == 0 {
            (text, symbol, color) = ("No grains found", "questionmark.circle.fill", .systemGray)
        } else if toCheck > 0 {
            (text, symbol, color) = ("\(toCheck) to check", "exclamationmark.circle.fill", Theme.markerOrange)
        } else if reviewedSome {
            (text, symbol, color) = ("Checked by you", "checkmark.seal.fill", Theme.markerGreen)
        } else if res.photoTip != nil {
            (text, symbol, color) = ("Photo could be better", "lightbulb.fill", .systemYellow)
        } else {
            (text, symbol, color) = ("Looks exact", "checkmark.circle.fill", Theme.markerGreen)
        }
        c.title = text
        c.image = Theme.symbol(symbol, 12)
        c.baseBackgroundColor = color.withAlphaComponent(0.22)
        c.baseForegroundColor = color
        c.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { a in
            var a = a
            a.font = UIFont.systemFont(ofSize: 13, weight: .semibold)
            return a
        }
        UIView.transition(with: statusPill, duration: 0.25, options: .transitionCrossDissolve, animations: {
            self.statusPill.configuration = c
        })
    }

    private func statusTapped() {
        guard let res = result else { return }
        if countView.markers.count(of: .uncertain) > 0 {
            startReview()
        } else if res.photoTip != nil {
            setTip(visible: tipBanner.alpha < 0.5)
        }
    }

    private func setTip(visible: Bool) {
        UIView.animate(withDuration: 0.45, delay: 0, usingSpringWithDamping: 0.8, initialSpringVelocity: 0.4, options: [], animations: {
            self.tipBanner.alpha = visible ? 1 : 0
            self.tipBanner.transform = visible ? .identity : CGAffineTransform(translationX: 0, y: -12)
        })
    }

    // ------------------------------------------------------------------ review

    private func startReview() {
        let m = countView.markers
        let first = m.next(.uncertain, from: 0)
        guard first >= 0 else {
            Toast.show("Nothing left to check", in: view)
            return
        }
        reviewTotal = m.count(of: .uncertain)
        setTip(visible: false)
        reviewCard.isHidden = false
        reviewCard.transform = CGAffineTransform(translationX: 0, y: 40)
        UIView.animate(withDuration: 0.5, delay: 0, usingSpringWithDamping: 0.82, initialSpringVelocity: 0.4, options: [], animations: {
            self.toolbar.alpha = 0
            self.toolbar.transform = CGAffineTransform(translationX: 0, y: 30)
            self.reviewCard.alpha = 1
            self.reviewCard.transform = .identity
        })
        view.layoutIfNeeded()
        // While reviewing, the free area above the card is where the grain is shown.
        countView.fitInsets.bottom = view.bounds.height - reviewCard.frame.minY + 12
        goTo(m[first])
    }

    private func goTo(_ mk: Markers.Marker) {
        let m = countView.markers
        reviewing = mk
        countView.highlighted = mk
        countView.focus(on: mk)
        let left = m.count(of: .uncertain)
        let number = (m.index(of: mk) ?? 0) + 1
        reviewTitle.text = "Grain \(number)"
        reviewSubtitle.text = "Is this exactly one grain? \(left) left to check. Missed one nearby? Tap the empty spot to add it."
        reviewProgress.setProgress(Float(reviewTotal - left) / Float(max(1, reviewTotal)), animated: true)
    }

    private func reviewAnswer(keep: Bool) {
        guard let mk = reviewing else { return }
        let m = countView.markers
        let from = m.index(of: mk) ?? 0
        if keep { countView.confirm(mk) } else { countView.remove(mk) }
        reviewedSome = true
        updateStatus()
        advance(from: keep ? from + 1 : from)
    }

    private func reviewSkip() {
        guard let mk = reviewing else { return }
        let m = countView.markers
        let from = (m.index(of: mk) ?? 0) + 1
        let next = m.next(.uncertain, from: from)
        if next >= 0 && m[next] !== mk { goTo(m[next]) } else { endReview() }
    }

    private func advance(from: Int) {
        let m = countView.markers
        let next = m.next(.uncertain, from: from)
        if next >= 0 {
            goTo(m[next])
        } else {
            reviewProgress.setProgress(1, animated: true)
            Theme.success()
            Toast.show("All grains checked: \(m.count) is exact", symbol: "checkmark.seal.fill", in: view)
            endReview()
        }
    }

    private func endReview() {
        reviewing = nil
        countView.highlighted = nil
        countView.fitInsets = lastInsets
        countView.resetFit(animated: true)
        UIView.animate(withDuration: 0.45, delay: 0, usingSpringWithDamping: 0.85, initialSpringVelocity: 0.4, options: [], animations: {
            self.toolbar.alpha = 1
            self.toolbar.transform = .identity
            self.reviewCard.alpha = 0
            self.reviewCard.transform = CGAffineTransform(translationX: 0, y: 40)
        }, completion: { _ in
            if self.reviewing == nil { self.reviewCard.isHidden = true }
        })
        updateStatus()
    }

    // ------------------------------------------------------------------ output

    private func keepInHistory() {
        guard let res = result, countView.hasData, let picture = countView.renderExport() else { return }
        let m = countView.markers
        let entry = HistoryEntry(id: historyID, date: Date(), count: m.count, toCheck: m.count(of: .uncertain),
                                 ignored: res.ignoredCount)
        HistoryStore.shared.save(entry, image: picture)
    }

    private func saveToPhotos() {
        guard result != nil else { return }
        if savedToPhotos {
            Toast.show("Already in Photos", in: view)
            return
        }
        guard let png = countView.renderExport()?.pngData() else {
            Toast.show("Could not save the picture", symbol: "exclamationmark.triangle.fill", in: view)
            return
        }
        let name = "grain_count_\(countView.markers.count)_\(Int(Date().timeIntervalSince1970 * 1000)).png"
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async {
                    Toast.show("Allow adding photos in Settings to save", symbol: "exclamationmark.triangle.fill", in: self.view)
                }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                let opts = PHAssetResourceCreationOptions()
                opts.originalFilename = name
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: png, options: opts)
            }) { ok, _ in
                DispatchQueue.main.async {
                    self.savedToPhotos = ok
                    if ok { Theme.success() }
                    Toast.show(ok ? "Saved to Photos" : "Could not save the picture",
                               symbol: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill", in: self.view)
                }
            }
        }
    }

    private func share() {
        guard result != nil, let png = countView.renderExport()?.pngData() else { return }
        let n = countView.markers.count
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("grain_count_\(n).png")
        guard (try? png.write(to: url, options: .atomic)) != nil else { return }
        let vc = UIActivityViewController(activityItems: [url, "Grain count: \(n)"], applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = shareButton
        present(vc, animated: true)
    }
}
