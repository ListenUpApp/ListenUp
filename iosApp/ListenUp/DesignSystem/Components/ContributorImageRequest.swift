import Foundation
import Nuke
@preconcurrency import Shared

/// Builds Nuke image requests for contributor photos: the durable local file if it has been
/// cached, else the authenticated server URL `{activeUrl}/api/v1/contributors/{id}/photo`.
///
/// On the server-fallback branch it also kicks off `ensureContributorImageCached`, so the first
/// time a contributor photo is streamed it is persisted to disk for offline use — independent of
/// Nuke's evictable cache. Mirrors `CoverImageRequest` and the Compose `ContributorCoverImage`.
enum ContributorImageRequest {
    @MainActor
    static func contributor(
        contributorId: String,
        imagePath: String?,
        targetPixels: CGFloat
    ) async -> ImageRequest? {
        let processors = AuthenticatedImageRequest.processors(targetPixels: targetPixels)
        let key = cacheKey(contributorId: contributorId, imagePath: imagePath)

        let repository = KoinHelper.shared.getImageRepository()

        // Offline fast path: a previously cached photo on disk.
        if repository.contributorImageExists(contributorId: contributorId) {
            let path = repository.getContributorImagePath(contributorId: contributorId)
            return AuthenticatedImageRequest.localFile(path, processors: processors, cacheKey: key)
        }

        // No durable file yet — persist this streamed photo on disk for offline use (fire-and-forget,
        // no-op once it exists), then stream from the server now.
        repository.ensureContributorImageCached(contributorId: contributorId)

        guard let base = try? await KoinHelper.shared.activeServerUrl(),
              let url = photoURL(base: base, contributorId: contributorId, imagePath: imagePath)
        else { return nil }

        return await AuthenticatedImageRequest.authenticated(url: url, processors: processors, cacheKey: key)
    }

    /// The content-addressed Nuke cache key for a contributor photo. Folding the server's
    /// content-addressed `imagePath` (`contributors/{sha}.jpg`) into the key means a re-scraped
    /// photo (new path) busts the cached image instead of serving the stale one — mirroring the
    /// Compose `contributorCacheKey`. Pure, so it's unit-tested. `nil` imagePath falls back to a
    /// stable per-id key.
    static func cacheKey(contributorId: String, imagePath: String?) -> String {
        "\(contributorId):\(imagePath ?? "contributor")"
    }

    /// The authenticated contributor-photo endpoint for a server base URL. Pure, so the endpoint
    /// shape is unit-tested. Returns nil for a blank base.
    ///
    /// Content-addresses the URL with the server's `imagePath` (the photo's version) via a `?v=`
    /// query item, so a re-scrape changes the URL itself — busting `URLSession`'s `URLCache` (keyed
    /// by URL), not just Nuke's cache key. The server ignores `?v`. A nil/blank imagePath yields the
    /// bare endpoint (nothing to version).
    static func photoURL(base: String, contributorId: String, imagePath: String?) -> URL? {
        guard !base.isEmpty else { return nil }
        var components = URLComponents(string: "\(base)/api/v1/contributors/\(contributorId)/photo")
        if let imagePath, !imagePath.isEmpty {
            components?.queryItems = [URLQueryItem(name: "v", value: imagePath)]
        }
        return components?.url
    }
}
