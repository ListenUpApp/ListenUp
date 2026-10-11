import Foundation
import UIKit

/// Turns a photo the user just picked into the bytes we upload: an upright JPEG whose longest edge
/// is bounded, so a 48-megapixel photo is never materialised as a full-size bitmap.
enum PickedImage {
    /// Longest edge of an uploaded picked image, in pixels. Parity with Android's
    /// `PICKED_IMAGE_MAX_EDGE_PX`; if it moves, move both.
    static let maxEdgePixels = 2048

    /// The picked `data` (any format ImageIO reads, e.g. HEIC) as a JPEG no larger than
    /// ``maxEdgePixels`` on its longest edge, with the camera orientation applied. `nil` when the
    /// bytes are not a decodable image. Does no view work, so it is safe off the main actor.
    nonisolated static func normalizedJPEG(from data: Data, quality: CGFloat = 0.9) -> Data? {
        ImageDownsampler.downsampledImage(data: data, maxPixelSize: maxEdgePixels)?
            .jpegData(compressionQuality: quality)
    }
}
