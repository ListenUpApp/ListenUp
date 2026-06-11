import ImageIO
import UIKit

/// Decodes images downsampled via ImageIO so the full-resolution bitmap is
/// never materialized. Shared by `BookCoverImage` and `SystemIntegration`.
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
