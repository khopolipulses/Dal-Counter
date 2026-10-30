import UIKit

/// One saved count: the numbered picture lives next to the index as <id>.jpg (+ <id>_t.jpg thumbnail).
struct HistoryEntry: Codable {
    let id: String
    var date: Date
    var count: Int
    var toCheck: Int
    var ignored: Int
}

extension Notification.Name {
    static let historyChanged = Notification.Name("historyChanged")
}

/// Past counts, kept only on this device (Application Support/History).
final class HistoryStore {
    static let shared = HistoryStore()

    private let dir: URL
    private let thumbs = NSCache<NSString, UIImage>()
    private(set) var entries: [HistoryEntry] = []
    /// Every write and delete runs here, in order: a delete can never be overtaken by a save still in flight.
    private let io = DispatchQueue(label: "history.io")

    /// Ids are UUIDs made by this app; anything else is never used as a file name.
    static func validId(_ id: String) -> Bool {
        return !id.isEmpty && id.count <= 64 && id.allSatisfy { $0.isHexDigit || $0 == "-" }
    }

    /// A file in the app's temporary share folder (emptied at launch), for handing a picture to another app.
    static func shareFile(named name: String) -> URL? {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent(name)
    }

    static func clearShareFiles() {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.removeItem(at: d)
    }

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("History", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                 attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        // Counts stay on this phone: not in iCloud or computer backups.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(values)
        if let data = try? Data(contentsOf: indexURL),
           let list = try? JSONDecoder().decode([HistoryEntry].self, from: data) {
            entries = list.filter { HistoryStore.validId($0.id) }
        }
    }

    private var indexURL: URL { dir.appendingPathComponent("index.json") }

    func imageURL(_ id: String) -> URL { dir.appendingPathComponent(id + ".jpg") }

    private func thumbURL(_ id: String) -> URL { dir.appendingPathComponent(id + "_t.jpg") }

    /// Adds or replaces an entry and its picture (the files are written off the main thread).
    func save(_ entry: HistoryEntry, image: UIImage) {
        guard HistoryStore.validId(entry.id) else { return }
        if let i = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[i] = entry
        } else {
            entries.insert(entry, at: 0)
        }
        let thumb = HistoryStore.thumbnail(image, side: 240)
        thumbs.setObject(thumb, forKey: entry.id as NSString)
        let list = entries
        let full = imageURL(entry.id), small = thumbURL(entry.id), index = indexURL
        io.async {
            try? image.jpegData(compressionQuality: 0.9)?.write(to: full, options: .atomic)
            try? thumb.jpegData(compressionQuality: 0.8)?.write(to: small, options: .atomic)
            try? JSONEncoder().encode(list).write(to: index, options: .atomic)
        }
        NotificationCenter.default.post(name: .historyChanged, object: nil)
    }

    func delete(_ id: String) {
        guard HistoryStore.validId(id) else { return }
        entries.removeAll { $0.id == id }
        thumbs.removeObject(forKey: id as NSString)
        let list = entries, full = imageURL(id), small = thumbURL(id), index = indexURL
        io.async {
            try? FileManager.default.removeItem(at: full)
            try? FileManager.default.removeItem(at: small)
            try? JSONEncoder().encode(list).write(to: index, options: .atomic)
        }
        NotificationCenter.default.post(name: .historyChanged, object: nil)
    }

    /// Waits for pending writes (used before reading a picture that may still be being written).
    func flush() {
        io.sync {}
    }

    func thumbnail(_ id: String) -> UIImage? {
        if let t = thumbs.object(forKey: id as NSString) { return t }
        guard let t = UIImage(contentsOfFile: thumbURL(id).path) else { return nil }
        thumbs.setObject(t, forKey: id as NSString)
        return t
    }

    func image(_ id: String) -> UIImage? {
        flush()
        return UIImage(contentsOfFile: imageURL(id).path)
    }

    private static func thumbnail(_ image: UIImage, side: CGFloat) -> UIImage {
        let k = side / max(1, min(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * k, height: image.size.height * k)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}

extension Date {
    /// "Today, 18:42", "Yesterday, 09:10" or "12 Oct, 14:03".
    var friendly: String {
        let cal = Calendar.current
        let time = DateFormatter.localizedString(from: self, dateStyle: .none, timeStyle: .short)
        if cal.isDateInToday(self) { return "Today, " + time }
        if cal.isDateInYesterday(self) { return "Yesterday, " + time }
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("d MMM")
        return f.string(from: self) + ", " + time
    }
}
