import ImageIO
import UIKit

/// Decodes images downsampled via ImageIO so the full-resolution bitmap is
/// never materialized. Shared by the cover tint, avatars, the staged profile photo, CarPlay and
/// `SystemIntegration`.
enum ImageDownsampler {
    /// Decode the image at `path` downsampled so its longest edge ≤ `maxPixelSize`
    /// (in pixels). Returns `nil` if the file can't be read or `maxPixelSize <= 0`.
    static func downsampledImage(atPath path: String, maxPixelSize: Int) -> UIImage? {
        guard maxPixelSize > 0,
              let source = CGImageSourceCreateWithURL(
                  URL(fileURLWithPath: path) as CFURL,
                  [kCGImageSourceShouldCache: false] as CFDictionary
              )
        else { return nil }
        return thumbnail(from: source, maxPixelSize: maxPixelSize)
    }

    /// Decode in-memory image `data` (e.g. a photo just picked) downsampled so its longest edge
    /// ≤ `maxPixelSize`. Returns `nil` if the data can't be decoded or `maxPixelSize <= 0`.
    static func downsampledImage(data: Data, maxPixelSize: Int) -> UIImage? {
        guard maxPixelSize > 0,
              let source = CGImageSourceCreateWithData(
                  data as CFData,
                  [kCGImageSourceShouldCache: false] as CFDictionary
              )
        else { return nil }
        return thumbnail(from: source, maxPixelSize: maxPixelSize)
    }

    private static func thumbnail(from source: CGImageSource, maxPixelSize: Int) -> UIImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
