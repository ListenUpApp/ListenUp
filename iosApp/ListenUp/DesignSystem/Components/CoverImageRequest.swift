import Foundation
import Nuke
@preconcurrency import Shared

/// Builds Nuke image requests for book covers: the local file if a downloaded `coverPath`
/// exists, else the authenticated server URL `{activeUrl}/api/v1/covers/{bookId}`.
///
/// The authenticated request carries an `Authorization: Bearer` header but keys its cache
/// on the cover URL (Nuke's default), so a cached cover survives token refresh and never
/// re-downloads just because the access token rotated.
enum CoverImageRequest {
    @MainActor
    static func book(
        bookId: String?,
        coverPath: String?,
        coverHash: String?,
        targetPixels: CGFloat
    ) async -> ImageRequest? {
        let processors = AuthenticatedImageRequest.processors(targetPixels: targetPixels)

        if let coverPath, !coverPath.isEmpty {
            let cacheKey = coverCacheKey(identity: bookId ?? coverPath, coverHash: coverHash)
            return AuthenticatedImageRequest.localFile(coverPath, processors: processors, cacheKey: cacheKey)
        }

        guard let bookId, !bookId.isEmpty else { return nil }

        // No durable file yet — kick off a background download so this streamed cover is
        // persisted to disk for offline use, independent of Nuke's evictable cache. Fire-and-forget
        // on the repository's app scope; no-op once the cover exists locally. (Mirrors the Compose
        // BookCoverImage server fallback.)
        KoinHelper.shared.ensureBookCoverCached(bookId: bookId)

        guard let base = (try? await KoinHelper.shared.activeServerUrl()), !base.isEmpty,
              let url = URL(string: "\(base)/api/v1/covers/\(bookId)")
        else { return nil }

        let cacheKey = coverCacheKey(identity: bookId, coverHash: coverHash)
        return await AuthenticatedImageRequest.authenticated(url: url, processors: processors, cacheKey: cacheKey)
    }

    /// Fold the cover content hash into Nuke's cache identity so a new cover at the same stable
    /// path/URL busts the old entry — mirrors Android's `"$bookId:$coverHash"` Coil key. The key is
    /// token-independent, so a cached cover still survives access-token rotation.
    private static func coverCacheKey(identity: String, coverHash: String?) -> String {
        "\(identity):\(coverHash ?? "cover")"
    }
}
