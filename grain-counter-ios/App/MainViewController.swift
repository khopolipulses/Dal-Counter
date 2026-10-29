import Photos
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/**
 * Single-screen grain counter: take or pick a photo, count the grains on the phone (no internet),
 * fix any miss by tapping, then save or share the numbered picture.
 */
final class MainViewController: UIViewController {

    /// Photos are processed at this long-side size: fast on a phone and as accurate as full size.
    private static let maxSide = 1280

    private let countView = CountView()
    private let total = UILabel()
    private let hintLabel = UILabel()
    private var undoButton: UIButton!
    private var shareButton: UIButton!
    private var busy = false
    /// The picture was already saved to Photos and has not changed since.
    private var savedToPhotos = false

    override func viewDidLoad() {
        super.viewDidLoad()
        overrideUserInterfaceStyle = .dark
        view.backgroundColor = CountView.background
        buildUi()
    }

    // ------------------------------------------------------------------ UI

    private func buildUi() {
        let top = row([button("Photos") { [weak self] in self?.pickImage() },
                       button("Camera") { [weak self] in self?.openCamera() }])

        total.text = "Grain Counter"
        total.textColor = .white
        total.font = .boldSystemFont(ofSize: 28)
        total.textAlignment = .center

        hintLabel.font = .systemFont(ofSize: 13)
        hintLabel.textAlignment = .center
        hintLabel.numberOfLines = 0
        hintLabel.isHidden = true

        countView.onMarkersChanged = { [weak self] count, canUndo in
            self?.total.text = "Count: \(count)"
            self?.undoButton.isEnabled = canUndo
            self?.savedToPhotos = false // the picture changed, so the next save must write it again
        }

        let tip = UILabel()
        tip.text = "Pinch to zoom. Tap an empty spot to add a missed grain, tap a circle to remove it."
        tip.textColor = .lightGray
        tip.font = .systemFont(ofSize: 12)
        tip.textAlignment = .center
        tip.numberOfLines = 0

        undoButton = button("Undo") { [weak self] in self?.countView.undo() }
        undoButton.isEnabled = false
        shareButton = button("Share") { [weak self] in self?.share() }
        let bottom = row([undoButton,
                          button("Fit") { [weak self] in self?.countView.resetFit() },
                          button("Save") { [weak self] in self?.save() },
                          shareButton])

        let stack = UIStackView(arrangedSubviews: [top, total, hintLabel, countView, tip, bottom])
        stack.axis = .vertical
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        countView.setContentHuggingPriority(.defaultLow, for: .vertical)
        countView.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        view.addSubview(stack)
        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: g.topAnchor, constant: 6),
            stack.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -6),
            stack.leadingAnchor.constraint(equalTo: g.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: g.trailingAnchor),
        ])
    }

    private func row(_ buttons: [UIButton]) -> UIStackView {
        let r = UIStackView(arrangedSubviews: buttons)
        r.axis = .horizontal
        r.distribution = .fillEqually
        r.spacing = 6
        r.isLayoutMarginsRelativeArrangement = true
        r.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8)
        return r
    }

    private func button(_ title: String, _ action: @escaping () -> Void) -> UIButton {
        var config = UIButton.Configuration.gray()
        config.title = title
        return UIButton(configuration: config, primaryAction: UIAction { _ in action() })
    }

    private func showHint(_ text: String?) {
        if let text = text {
            hintLabel.text = text
            hintLabel.textColor = UIColor(red: 1, green: 190 / 255, blue: 80 / 255, alpha: 1)
        } else {
            hintLabel.text = "Clean photo: this count should be exact."
            hintLabel.textColor = UIColor(red: 120 / 255, green: 220 / 255, blue: 140 / 255, alpha: 1)
        }
        hintLabel.isHidden = false
    }

    /// A short message that fades away by itself (like an Android toast).
    private func toast(_ message: String) {
        let l = PaddedLabel()
        l.text = message
        l.textColor = .white
        l.font = .systemFont(ofSize: 15)
        l.backgroundColor = UIColor(white: 0.1, alpha: 0.9)
        l.layer.cornerRadius = 10
        l.clipsToBounds = true
        l.numberOfLines = 0
        l.textAlignment = .center
        l.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(l)
        NSLayoutConstraint.activate([
            l.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            l.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -80),
            l.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -40),
        ])
        UIView.animate(withDuration: 0.3, delay: 2, options: [], animations: { l.alpha = 0 }) { _ in l.removeFromSuperview() }
    }

    // ------------------------------------------------------------------ input

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
            toast("No camera available")
            return
        }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = self
        present(picker, animated: true)
    }

    // ------------------------------------------------------------------ counting

    /// Decodes and counts off the main thread. `decode` runs in the background and returns an upright image.
    private func load(_ decode: @escaping () -> CGImage?) {
        if busy { return }
        busy = true
        total.text = "Counting..."
        DispatchQueue.global(qos: .userInitiated).async {
            let px = decode().flatMap { MainViewController.pixels($0) }
            let res = px.map { DalCounter.count(argb: $0.argb, width: $0.w, height: $0.h) }
            DispatchQueue.main.async {
                self.busy = false
                guard let px = px, let res = res else {
                    self.total.text = "Could not read that image"
                    return
                }
                self.countView.setData(px.image, res)
                self.showHint(res.hint)
            }
        }
    }

    /// Photo-library file: decoded straight to MAX_SIDE with its EXIF rotation applied (cheap on memory).
    private static func decode(data: Data) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        let pw = props?[kCGImagePropertyPixelWidth] as? Int ?? maxSide
        let ph = props?[kCGImagePropertyPixelHeight] as? Int ?? maxSide
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, min(maxSide, max(pw, ph))),
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }

    /// Camera photo: drawn upright at MAX_SIDE.
    private static func decode(image: UIImage) -> CGImage? {
        let longSide = max(image.size.width, image.size.height)
        guard longSide > 0 else { return nil }
        let k = min(1, CGFloat(maxSide) / longSide)
        let size = CGSize(width: (image.size.width * k).rounded(), height: (image.size.height * k).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }.cgImage
    }

    /// 0xAARRGGBB pixels (what the engine expects) plus the exact same pixels as the image to show.
    private static func pixels(_ cg: CGImage) -> (argb: [UInt32], w: Int, h: Int, image: UIImage)? {
        let w = cg.width, h = cg.height
        guard w > 0, h > 0, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return nil }
        ctx.setFillColor(UIColor.white.cgColor)   // transparent images count as if on white paper
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let data = ctx.data, let shown = ctx.makeImage() else { return nil }
        // 32-bit little-endian BGRA in memory reads back as 0xAARRGGBB words.
        let argb = Array(UnsafeBufferPointer(start: data.bindMemory(to: UInt32.self, capacity: w * h), count: w * h))
        return (argb, w, h, UIImage(cgImage: shown, scale: 1, orientation: .up))
    }

    // ------------------------------------------------------------------ output

    private func renderPNG() -> Data? {
        return countView.renderExport()?.pngData()
    }

    private func save() {
        guard countView.hasData else {
            toast("Load a photo first")
            return
        }
        if savedToPhotos {
            toast("Saved to Photos")
            return
        }
        guard let png = renderPNG() else {
            toast("Could not save the image")
            return
        }
        let name = "grain_count_\(countView.count)_\(Int(Date().timeIntervalSince1970 * 1000)).png"
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async { self.toast("Allow adding photos in Settings to save") }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                let opts = PHAssetResourceCreationOptions()
                opts.originalFilename = name
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: png, options: opts)
            }) { ok, _ in
                DispatchQueue.main.async {
                    self.savedToPhotos = ok
                    self.toast(ok ? "Saved to Photos" : "Could not save the image")
                }
            }
        }
    }

    private func share() {
        guard countView.hasData else {
            toast("Load a photo first")
            return
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("grain_count_\(countView.count).png")
        guard let png = renderPNG(), (try? png.write(to: url, options: .atomic)) != nil else {
            toast("Could not save the image")
            return
        }
        let vc = UIActivityViewController(activityItems: [url, "Grain count: \(countView.count)"], applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = shareButton   // iPad shows it as a popover
        present(vc, animated: true)
    }
}

extension MainViewController: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider,
              provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else { return }
        provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
            DispatchQueue.main.async {
                guard let data = data else {
                    self.total.text = "Could not read that image"
                    return
                }
                self.load { MainViewController.decode(data: data) }
            }
        }
    }
}

extension MainViewController: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(_ picker: UIImagePickerController,
                               didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        guard let image = info[.originalImage] as? UIImage else { return }
        load { MainViewController.decode(image: image) }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
    }
}

/// UILabel with some room around the text, for the toast.
private final class PaddedLabel: UILabel {
    private let inset = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: inset))
    }

    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + inset.left + inset.right, height: s.height + inset.top + inset.bottom)
    }
}
