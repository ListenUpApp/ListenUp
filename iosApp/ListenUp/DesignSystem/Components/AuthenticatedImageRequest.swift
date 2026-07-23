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

    /// A Nuke request for a durable local file path. `cacheKey`, when supplied, overrides Nuke's
    /// URL-derived cache key so a content change at a stable path still busts the cache.
    static func localFile(
        _ path: String,
        processors: [any ImageProcessing],
        cacheKey: String? = nil
    ) -> ImageRequest {
        let userInfo: [ImageRequest.UserInfoKey: Any]? = cacheKey.map { [.imageIdKey: $0] }
        return ImageRequest(url: URL(fileURLWithPath: path), processors: processors, userInfo: userInfo)
    }

    /// A Nuke request for an authenticated server URL: attaches `Authorization: Bearer` but keys the
    /// cache on `cacheKey` when supplied (else the URL, Nuke's default) so a cached image survives
    /// token rotation and never re-downloads just because the access token rotated.
    @MainActor
    static func authenticated(
        url: URL,
        processors: [any ImageProcessing],
        cacheKey: String? = nil
    ) async -> ImageRequest {
        var urlRequest = URLRequest(url: url)
        let token = try? await KoinHelper.shared.accessToken()
        ImageTrace.log(
            "auth token=\(token == nil ? "MISSING" : "present") url=\(ImageTrace.tail(url.absoluteString, 56))"
        )
        if let token {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let userInfo: [ImageRequest.UserInfoKey: Any]? = cacheKey.map { [.imageIdKey: $0] }
        return ImageRequest(urlRequest: urlRequest, processors: processors, userInfo: userInfo)
    }
}
