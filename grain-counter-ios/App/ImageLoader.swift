import ImageIO
import UIKit

/// Turns picked or captured photos into upright, size-limited pixels for the counting engine.
enum ImageLoader {
    /// Photos are processed at this long-side size: fast on a phone and as accurate as full size.
    static let maxSide = 1280

    /// Photo-library file: decoded straight to maxSide with its EXIF rotation applied (cheap on memory).
    static func decode(data: Data) -> CGImage? {
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

    /// Camera photo: drawn upright at maxSide.
    static func decode(image: UIImage) -> CGImage? {
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
    static func pixels(_ cg: CGImage) -> (argb: [UInt32], w: Int, h: Int, image: UIImage)? {
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
}
