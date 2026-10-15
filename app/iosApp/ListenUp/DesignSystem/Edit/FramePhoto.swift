import Foundation
import Nuke
import UIKit

/// Fetches the photo Adjust Photo opens, at the size Move and Scale needs: big enough to stay sharp at 4×
/// in a 420 pt cut-out, bounded so a huge photo is never decoded at full size.
enum FramePhoto {
    /// The longest edge Move and Scale decodes, in pixels.
    static let editorPixels: CGFloat = 2048

    /// A saved contributor photo, from the same sources its avatar streams from.
    @MainActor
    static func contributor(contributorId: String, imagePath: String?) async -> UIImage? {
        let request = await ContributorImageRequest.contributor(
            contributorId: contributorId,
            imagePath: imagePath,
            targetPixels: editorPixels
        )
        return await image(for: request)
    }

    /// A user's saved avatar, at its current version.
    @MainActor
    static func avatar(userId: String, version: Int64) async -> UIImage? {
        let request = await UserAvatarImageRequest.avatar(userId: userId, version: version, targetPixels: editorPixels)
        return await image(for: request)
    }

    /// A photo already on disk, such as a staged pick.
    @concurrent
    nonisolated static func file(atPath path: String) async -> UIImage? {
        ImageDownsampler.downsampledImage(atPath: path, maxPixelSize: Int(editorPixels))
    }

    /// In-memory bytes, such as a staged avatar upload.
    @concurrent
    nonisolated static func data(_ data: Data) async -> UIImage? {
        ImageDownsampler.downsampledImage(data: data, maxPixelSize: Int(editorPixels))
    }

    private static func image(for request: ImageRequest?) async -> UIImage? {
        guard let request else { return nil }
        return try? await ImagePipeline.shared.image(for: request)
    }
}
