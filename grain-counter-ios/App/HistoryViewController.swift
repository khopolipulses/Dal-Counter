import UIKit

/// Every saved count, newest first. Swipe left to delete, tap to open.
final class HistoryViewController: UITableViewController {

    init() {
        super.init(style: .insetGrouped)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "History"
        navigationItem.largeTitleDisplayMode = .always
        navigationItem.backButtonDisplayMode = .minimal
        tableView.backgroundView = MeshBackgroundView()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        tableView.rowHeight = 76
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: .historyChanged, object: nil)
        reload()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
    }

    @objc private func reload() {
        tableView.reloadData()
        if HistoryStore.shared.entries.isEmpty {
            let label = UILabel()
            label.text = "No counts yet.\nYour counts are kept here, only on this phone."
            label.numberOfLines = 0
            label.textAlignment = .center
            label.textColor = .secondaryLabel
            label.font = UIFont.preferredFont(forTextStyle: .body)
            tableView.tableFooterView = nil
            let holder = UIView(frame: CGRect(x: 0, y: 0, width: 1, height: 200))
            label.frame = holder.bounds.insetBy(dx: 20, dy: 0)
            label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            holder.addSubview(label)
            tableView.tableHeaderView = holder
        } else {
            tableView.tableHeaderView = nil
        }
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return HistoryStore.shared.entries.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        let e = HistoryStore.shared.entries[indexPath.row]
        var c = UIListContentConfiguration.subtitleCell()
        c.text = e.count == 1 ? "1 grain" : "\(e.count) grains"
        c.textProperties.font = Theme.rounded(19, .semibold)
        c.secondaryText = e.date.friendly + (e.toCheck > 0 ? " · \(e.toCheck) not checked" : " · checked")
        c.secondaryTextProperties.color = .secondaryLabel
        c.image = HistoryStore.shared.thumbnail(e.id) ?? UIImage(systemName: "photo")
        c.imageProperties.maximumSize = CGSize(width: 56, height: 56)
        c.imageProperties.reservedLayoutSize = CGSize(width: 56, height: 56)
        c.imageProperties.cornerRadius = 12
        c.imageToTextPadding = 14
        cell.contentConfiguration = c
        var bg = UIBackgroundConfiguration.listGroupedCell()
        bg.visualEffect = UIBlurEffect(style: .systemThinMaterial)
        bg.backgroundColor = UIColor.systemBackground.withAlphaComponent(0.2)
        cell.backgroundConfiguration = bg
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        Theme.tap()
        let e = HistoryStore.shared.entries[indexPath.row]
        navigationController?.pushViewController(HistoryDetailViewController(entry: e), animated: true)
    }

    override func tableView(_ tableView: UITableView,
                            trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let delete = UIContextualAction(style: .destructive, title: "Delete") { _, _, done in
            let id = HistoryStore.shared.entries[indexPath.row].id
            NotificationCenter.default.removeObserver(self, name: .historyChanged, object: nil)
            HistoryStore.shared.delete(id)
            tableView.deleteRows(at: [indexPath], with: .automatic)
            NotificationCenter.default.addObserver(self, selector: #selector(self.reload), name: .historyChanged, object: nil)
            Theme.tap(.medium)
            done(true)
        }
        delete.image = UIImage(systemName: "trash")
        return UISwipeActionsConfiguration(actions: [delete])
    }
}

/// One saved count: the numbered picture, zoomable, with share and delete.
final class HistoryDetailViewController: UIViewController, UIScrollViewDelegate {
    private let entry: HistoryEntry
    private let scroll = UIScrollView()
    private let imageView = UIImageView()

    init(entry: HistoryEntry) {
        self.entry = entry
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        overrideUserInterfaceStyle = .dark
        view.backgroundColor = Theme.canvas
        title = entry.count == 1 ? "1 grain" : "\(entry.count) grains"
        navigationItem.largeTitleDisplayMode = .never
        // This screen is dark (a photo), so its bar is too: dark glass, white title, whatever the system mode.
        let bar = UINavigationBarAppearance()
        bar.configureWithDefaultBackground()
        bar.backgroundEffect = UIBlurEffect(style: .systemThinMaterialDark)
        bar.titleTextAttributes = [.foregroundColor: UIColor.white]
        navigationItem.standardAppearance = bar
        navigationItem.scrollEdgeAppearance = bar
        navigationItem.compactAppearance = bar
        let share = UIBarButtonItem(barButtonSystemItem: .action, target: self, action: #selector(sharePicture))
        let delete = UIBarButtonItem(barButtonSystemItem: .trash, target: self, action: #selector(confirmDelete))
        navigationItem.rightBarButtonItems = [share, delete]

        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.delegate = self
        scroll.maximumZoomScale = 8
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        view.addSubview(scroll)
        imageView.image = HistoryStore.shared.image(entry.id)
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(imageView)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            imageView.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            imageView.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
        ])
        let double = UITapGestureRecognizer(target: self, action: #selector(toggleZoom(_:)))
        double.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(double)

        let caption = UILabel()
        caption.text = entry.date.friendly + (entry.toCheck > 0 ? " · \(entry.toCheck) not checked" : " · checked")
        caption.font = UIFont.preferredFont(forTextStyle: .footnote)
        caption.textColor = .secondaryLabel
        let pill = GlassView(cornerRadius: 16, style: .systemThinMaterialDark)
        pill.embed(caption, padding: UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14))
        pill.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(pill)
        NSLayoutConstraint.activate([
            pill.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            pill.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    @objc private func toggleZoom(_ g: UITapGestureRecognizer) {
        if scroll.zoomScale > 1.1 {
            scroll.setZoomScale(1, animated: true)
        } else {
            let p = g.location(in: imageView)
            scroll.zoom(to: CGRect(x: p.x - 60, y: p.y - 60, width: 120, height: 120), animated: true)
        }
    }

    @objc private func sharePicture() {
        let url = HistoryStore.shared.imageURL(entry.id)
        let vc = UIActivityViewController(activityItems: [url, "Grain count: \(entry.count)"], applicationActivities: nil)
        vc.popoverPresentationController?.barButtonItem = navigationItem.rightBarButtonItems?.first
        present(vc, animated: true)
    }

    @objc private func confirmDelete() {
        let a = UIAlertController(title: "Delete this count?", message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Delete", style: .destructive) { _ in
            HistoryStore.shared.delete(self.entry.id)
            self.navigationController?.popViewController(animated: true)
        })
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        a.popoverPresentationController?.barButtonItem = navigationItem.rightBarButtonItems?.last
        present(a, animated: true)
    }
}
