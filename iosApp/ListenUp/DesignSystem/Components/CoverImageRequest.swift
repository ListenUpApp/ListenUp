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
            let cacheKey = localFileCacheKey(bookId: bookId, coverPath: coverPath, coverHash: coverHash)
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

    /// Cache key for a **local cover file**. A `bookId`-scoped key is only content-safe when it
    /// folds in the content hash: during a book switch, `bookId` advances to book B while
    /// `coverPath` can still point at book A's file, so a `"B:cover"` key (bookId, no hash) would
    /// stamp book B's identity onto book A's bytes and poison the shared memory+disk cache — the
    /// "cover never changes" bug (RC-1). Without a hash we key by the **file path** instead, which
    /// is unique per file and can't be mis-associated. With a hash we keep the bookId-scoped key
    /// (mirrors the server-URL branch and Android's `"$bookId:$coverHash"`).
    static func localFileCacheKey(bookId: String?, coverPath: String, coverHash: String?) -> String {
        if let coverHash, !coverHash.isEmpty {
            return coverCacheKey(identity: bookId ?? coverPath, coverHash: coverHash)
        }
        return coverPath
    }

    /// Fold the cover content hash into Nuke's cache identity so a new cover at the same stable
    /// path/URL busts the old entry — mirrors Android's `"$bookId:$coverHash"` Coil key. The key is
    /// token-independent, so a cached cover still survives access-token rotation.
    private static func coverCacheKey(identity: String, coverHash: String?) -> String {
        "\(identity):\(coverHash ?? "cover")"
    }
}
