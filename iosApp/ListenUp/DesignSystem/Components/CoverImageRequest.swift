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

        // No hash → `nil` cache key → Nuke keys on the request URL (`/api/v1/covers/{bookId}`),
        // which is already unique per book. Never the `"bookId:cover"` custom key: it is the exact
        // poisoned key the old bug wrote, and during a switch (`coverPath == nil`, `bookId == B`)
        // it would let book A's still-cached bytes flash on book B. Passing `nil` also orphans any
        // stale `"<id>:cover"` disk entries. With a hash we keep the content-scoped `"<id>:<hash>"`.
        let cacheKey = contentHashKey(identity: bookId, coverHash: coverHash)
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
        contentHashKey(identity: bookId ?? coverPath, coverHash: coverHash) ?? coverPath
    }

    /// The content-scoped cache key `"<identity>:<coverHash>"`, or `nil` when there is no hash.
    /// Returning `nil` is deliberate: a hash-less key must fall back to Nuke's natural URL/path
    /// identity, never a bare-`bookId` key (which is not content-safe and poisons the cache).
    /// Token-independent, so a cached cover survives access-token rotation.
    static func contentHashKey(identity: String, coverHash: String?) -> String? {
        guard let coverHash, !coverHash.isEmpty else { return nil }
        return "\(identity):\(coverHash)"
    }
}
