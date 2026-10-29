import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// Start screen: take or choose a photo, see recent counts, and learn how to get an exact count.
final class HomeViewController: UIViewController {

    private let background = MeshBackgroundView()
    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private let recentStack = UIStackView()
    private var cards: [UIView] = []
    private var revealed = false

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Grain Counter"
        navigationItem.largeTitleDisplayMode = .always
        navigationItem.backButtonDisplayMode = .minimal
        navigationItem.rightBarButtonItem = UIBarButtonItem(image: Theme.symbol("clock.arrow.circlepath", 17), style: .plain,
                                                            target: self, action: #selector(openHistory))
        navigationItem.rightBarButtonItem?.accessibilityLabel = "History"

        background.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(background)
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.alwaysBounceVertical = true
        view.addSubview(scroll)
        setContentScrollView(scroll)

        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        // Phones: full width minus margins. iPad: a readable column.
        let fill = stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40)
        fill.priority = .defaultHigh
        NSLayoutConstraint.activate([
            fill,
            background.topAnchor.constraint(equalTo: view.topAnchor),
            background.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            background.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            background.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: view.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -32),
            stack.centerXAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerXAnchor),
            stack.widthAnchor.constraint(lessThanOrEqualToConstant: 640),
            stack.widthAnchor.constraint(lessThanOrEqualTo: scroll.frameLayoutGuide.widthAnchor, constant: -40),
        ])

        cards = [heroCard(), actions(), recentHeader(), recentStack, tipsCard()]
        cards.forEach { stack.addArrangedSubview($0) }
        stack.setCustomSpacing(28, after: cards[1])
        stack.setCustomSpacing(10, after: cards[2])
        stack.setCustomSpacing(28, after: recentStack)
        recentStack.axis = .vertical
        recentStack.spacing = 10

        NotificationCenter.default.addObserver(self, selector: #selector(reloadRecent), name: .historyChanged, object: nil)
        reloadRecent()
        cards.forEach { $0.alpha = 0 }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !revealed {
            revealed = true
            Theme.reveal(cards)
        }
    }

    // ------------------------------------------------------------------ content

    private func heroCard() -> UIView {
        let card = GlassView(cornerRadius: 28)
        let badge = UIView()
        badge.translatesAutoresizingMaskIntoConstraints = false
        badge.layer.cornerRadius = 18
        badge.layer.cornerCurve = .continuous
        let grad = CAGradientLayer()
        grad.colors = [UIColor(red: 1, green: 0.72, blue: 0.25, alpha: 1).cgColor, UIColor(red: 1, green: 0.45, blue: 0.15, alpha: 1).cgColor]
        grad.startPoint = CGPoint(x: 0, y: 0)
        grad.endPoint = CGPoint(x: 1, y: 1)
        grad.cornerRadius = 18
        grad.frame = CGRect(x: 0, y: 0, width: 56, height: 56)
        badge.layer.addSublayer(grad)
        let glyph = UIImageView(image: Theme.symbol("circle.hexagongrid.fill", 26))
        glyph.tintColor = .white
        glyph.translatesAutoresizingMaskIntoConstraints = false
        badge.addSubview(glyph)
        NSLayoutConstraint.activate([
            badge.widthAnchor.constraint(equalToConstant: 56), badge.heightAnchor.constraint(equalToConstant: 56),
            glyph.centerXAnchor.constraint(equalTo: badge.centerXAnchor), glyph.centerYAnchor.constraint(equalTo: badge.centerYAnchor),
        ])

        let title = UILabel()
        title.text = "Count any grain"
        title.font = Theme.rounded(26, .bold)
        let body = UILabel()
        body.text = "Dal, rice, wheat, seeds or beans, even mixed. Counted on your phone in a second; nothing is uploaded."
        body.font = UIFont.preferredFont(forTextStyle: .subheadline)
        body.textColor = .secondaryLabel
        body.numberOfLines = 0
        let text = UIStackView(arrangedSubviews: [title, body])
        text.axis = .vertical
        text.spacing = 4
        let row = UIStackView(arrangedSubviews: [badge, text])
        row.spacing = 16
        row.alignment = .center
        card.embed(row, padding: UIEdgeInsets(top: 20, left: 20, bottom: 20, right: 20))
        return card
    }

    private func actions() -> UIView {
        let take = PressButton.filled("Take Photo", symbol: "camera.fill") { [weak self] in self?.openCamera() }
        var c = UIButton.Configuration.filled()
        c.title = "Choose from Photos"
        c.image = Theme.symbol("photo.on.rectangle.angled", 17)
        c.imagePadding = 8
        c.cornerStyle = .large
        c.buttonSize = .large
        c.baseForegroundColor = .label
        c.background.visualEffect = UIBlurEffect(style: .systemThinMaterial)
        c.background.backgroundColor = UIColor.white.withAlphaComponent(0.05)
        c.background.strokeColor = UIColor.white.withAlphaComponent(0.3)
        c.background.strokeWidth = 1
        c.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { a in
            var a = a
            a.font = UIFont.systemFont(ofSize: 17, weight: .semibold)
            return a
        }
        let choose = PressButton(c) { [weak self] in self?.pickImage() }
        for b in [take, choose] { b.heightAnchor.constraint(greaterThanOrEqualToConstant: 56).isActive = true }
        let col = UIStackView(arrangedSubviews: [take, choose])
        col.axis = .vertical
        col.spacing = 12
        return col
    }

    private func recentHeader() -> UIView {
        let label = UILabel()
        label.text = "Recent"
        label.font = UIFont.systemFont(ofSize: 22, weight: .bold)
        var c = UIButton.Configuration.plain()
        c.title = "See All"
        c.baseForegroundColor = Theme.accent
        c.contentInsets = .zero
        let all = PressButton(c) { [weak self] in self?.openHistory() }
        all.setContentHuggingPriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [label, all])
        row.alignment = .lastBaseline
        return row
    }

    private func tipsCard() -> UIView {
        let card = GlassView(cornerRadius: 24)
        let title = UILabel()
        title.text = "For an exact count"
        title.font = UIFont.systemFont(ofSize: 17, weight: .semibold)
        let rows: [(String, String)] = [
            ("circle.grid.2x2", "Spread the grains so they do not touch"),
            ("square.fill", "Use one plain background that differs from the grains"),
            ("camera.viewfinder", "Hold the phone straight above, in even light"),
            ("checklist", "Review the orange rings: then the count is exact"),
            ("hand.tap", "Tap to add a missed grain, tap a ring to remove one"),
        ]
        let list = UIStackView(arrangedSubviews: [title])
        list.axis = .vertical
        list.spacing = 12
        for (symbol, text) in rows {
            let icon = UIImageView(image: Theme.symbol(symbol, 15))
            icon.tintColor = Theme.accent
            icon.contentMode = .center
            icon.widthAnchor.constraint(equalToConstant: 26).isActive = true
            let label = UILabel()
            label.text = text
            label.font = UIFont.preferredFont(forTextStyle: .subheadline)
            label.textColor = .secondaryLabel
            label.numberOfLines = 0
            let row = UIStackView(arrangedSubviews: [icon, label])
            row.spacing = 12
            row.alignment = .center
            list.addArrangedSubview(row)
        }
        card.embed(list, padding: UIEdgeInsets(top: 18, left: 18, bottom: 18, right: 18))
        return card
    }

    @objc private func reloadRecent() {
        recentStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let entries = HistoryStore.shared.entries.prefix(3)
        if entries.isEmpty {
            let card = GlassView(cornerRadius: 20)
            let icon = UIImageView(image: Theme.symbol("clock", 18))
            icon.tintColor = .tertiaryLabel
            let label = UILabel()
            label.text = "Your counts will appear here."
            label.font = UIFont.preferredFont(forTextStyle: .subheadline)
            label.textColor = .secondaryLabel
            let row = UIStackView(arrangedSubviews: [icon, label])
            row.spacing = 10
            card.embed(row, padding: UIEdgeInsets(top: 18, left: 18, bottom: 18, right: 18))
            recentStack.addArrangedSubview(card)
            return
        }
        for e in entries {
            let row = HistoryRow(entry: e)
            row.onTap = { [weak self] in self?.navigationController?.pushViewController(HistoryDetailViewController(entry: e), animated: true) }
            recentStack.addArrangedSubview(row)
        }
    }

    // ------------------------------------------------------------------ navigation

    @objc private func openHistory() {
        navigationController?.pushViewController(HistoryViewController(), animated: true)
    }

    private func openResult(_ source: @escaping ResultViewController.Source) {
        navigationController?.pushViewController(ResultViewController(source: source), animated: true)
    }

    private func pickImage() {
        var config = PHPickerConfiguration()  // no photo-library permission needed
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        present(picker, animated: true)
    }

    private func openCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            Toast.show("No camera available", symbol: "exclamationmark.triangle.fill", in: view)
            return
        }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = self
        present(picker, animated: true)
    }
}

extension HomeViewController: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider,
              provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else { return }
        openResult { done in
            provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                done(data.flatMap { ImageLoader.decode(data: $0) })
            }
        }
    }
}

extension HomeViewController: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(_ picker: UIImagePickerController,
                               didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        guard let image = info[.originalImage] as? UIImage else { return }
        openResult { done in
            DispatchQueue.global(qos: .userInitiated).async { done(ImageLoader.decode(image: image)) }
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
    }
}

/// A saved count as a glass row: thumbnail, number, when, and whether it was fully checked.
final class HistoryRow: GlassView {
    var onTap: (() -> Void)?

    init(entry e: HistoryEntry) {
        super.init(cornerRadius: 20)
        let thumb = UIImageView(image: HistoryStore.shared.thumbnail(e.id))
        thumb.contentMode = .scaleAspectFill
        thumb.clipsToBounds = true
        thumb.layer.cornerRadius = 12
        thumb.layer.cornerCurve = .continuous
        thumb.backgroundColor = .tertiarySystemFill
        thumb.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([thumb.widthAnchor.constraint(equalToConstant: 56), thumb.heightAnchor.constraint(equalToConstant: 56)])
        let title = UILabel()
        title.text = e.count == 1 ? "1 grain" : "\(e.count) grains"
        title.font = Theme.rounded(19, .semibold)
        let sub = UILabel()
        sub.text = e.date.friendly + (e.toCheck > 0 ? " · \(e.toCheck) not checked" : " · checked")
        sub.font = UIFont.preferredFont(forTextStyle: .footnote)
        sub.textColor = .secondaryLabel
        let text = UIStackView(arrangedSubviews: [title, sub])
        text.axis = .vertical
        text.spacing = 2
        let chevron = UIImageView(image: Theme.symbol("chevron.right", 13))
        chevron.tintColor = .tertiaryLabel
        chevron.setContentHuggingPriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [thumb, text, chevron])
        row.spacing = 14
        row.alignment = .center
        embed(row, padding: UIEdgeInsets(top: 10, left: 10, bottom: 10, right: 16))
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        accessibilityLabel = title.text
        isAccessibilityElement = true
        accessibilityTraits = .button
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    @objc private func tapped() {
        Theme.tap()
        UIView.animate(withDuration: 0.1, animations: { self.transform = CGAffineTransform(scaleX: 0.97, y: 0.97) }) { _ in
            UIView.animate(withDuration: 0.35, delay: 0, usingSpringWithDamping: 0.6, initialSpringVelocity: 0.5, options: [],
                           animations: { self.transform = .identity })
            self.onTap?()
        }
    }
}
