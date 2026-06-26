import Foundation
import Nuke
@preconcurrency import Shared

/// The shared authenticated-image-request core behind `CoverImageRequest` and
/// `ContributorImageRequest`: resize processors, the local-file request, and the authenticated
/// server request (Bearer header, URL-keyed cache so a cached image survives token rotation).
///
/// The two public builders keep their resource-specific head (how they resolve the local file and
/// the endpoint URL) and route the common tail through here, so the token/`URLRequest`/cache shape
/// lives in exactly one place. A future image resource (e.g. series artwork) should reuse this
/// rather than copying the tail a third time.
enum AuthenticatedImageRequest {
    /// Resize processors for a target pixel width (empty when `targetPixels <= 0`).
    @MainActor
    static func processors(targetPixels: CGFloat) -> [any ImageProcessing] {
        targetPixels > 0 ? [ImageProcessors.Resize(width: targetPixels, unit: .pixels)] : []
    }

    /// A Nuke request for a durable local file path.
    static func localFile(_ path: String, processors: [any ImageProcessing]) -> ImageRequest {
        ImageRequest(url: URL(fileURLWithPath: path), processors: processors)
    }

    /// A Nuke request for an authenticated server URL: attaches `Authorization: Bearer` but keys the
    /// cache on the URL (Nuke's default) so a cached image survives token rotation and never
    /// re-downloads just because the access token rotated.
    @MainActor
    static func authenticated(url: URL, processors: [any ImageProcessing]) async -> ImageRequest {
        var urlRequest = URLRequest(url: url)
        if let token = try? await KoinHelper.shared.accessToken() {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return ImageRequest(urlRequest: urlRequest, processors: processors)
    }
}
