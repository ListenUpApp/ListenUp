import UIKit

/// Cover decoding for CarPlay's Library list.
///
/// The Library is the whole collection (up to the head unit's `maximumItemCount`), so decoding every cover where the
/// list is built would hold the main actor for as long as the collection is large. The list appears with text rows
/// first, and these covers are filled in behind it.
enum CarPlayArtwork {
    /// Decodes the cover at `path` to `maxPixels` on its longest edge. Runs on the global executor, never the main
    /// actor: `ImageDownsampler` is ImageIO only (a `CGImage` wrapped in a `UIImage`), with no UIKit view API.
    @concurrent
    static func decode(path: String, maxPixels: Int) async -> UIImage? {
        ImageDownsampler.downsampledImage(atPath: path, maxPixelSize: maxPixels)
    }
}
