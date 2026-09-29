import Foundation
import Nuke

/// Builds Nuke image requests for book covers: the content-addressed server URL
/// `{activeUrl}/api/v1/covers/{bookId}?v={coverHash}` when the cover's version is known, else the
/// downloaded `coverPath`, else the bare server URL.
///
/// **Synchronous, so a cached cover shows in the first frame.** The request used to be built in a
/// `.task` that awaited the server URL and a fresh access token, so every cell painted its
/// placeholder first and faded the cover in even when Nuke held it in memory (2026-09-29 iOS audit,
/// performance). Now the base URL comes from `ImageServerBase` (mirrored on the main actor) and the
/// `Authorization` header is attached at load time by `AuthenticatingDataLoader`, so the request
/// needs nothing asynchronous and never embeds a token: a cached cover survives token rotation and
/// the cache keys stay token-free.
enum CoverImageRequest {
    @MainActor
    static func book(
        bookId: String?,
        coverPath: String?,
        coverHash: String?,
        targetPixels: CGFloat,
        serverBase: String?,
        serverFailed: Bool = false
    ) -> ImageRequest? {
        let processors = AuthenticatedImageRequest.processors(targetPixels: targetPixels)
        let base = serverFailed ? nil : serverBase.flatMap { $0.isEmpty ? nil : $0 }

        // When the server's content version (`coverHash`) is known, resolve from the content-addressed
        // server URL — NOT the durable local `coverPath`. The local file is keyed only by book id, so
        // after a re-scrape it can hold STALE bytes (the "iOS cover doesn't update" bug); the delete
        // meant to clear it is unreliable on this platform. Nuke's content-scoped `"<id>:<hash>"`
        // cacheKey busts on re-scrape and serves offline from Nuke's disk cache once fetched.
        //
        // Preferring the server is not the same as *requiring* it: when the server load fails
        // (offline with a cold cache, or no token can be minted) the view passes `serverFailed` and
        // this falls through to the durable local file below — a downloaded book must still show its
        // cover with no network, and both branches key on the same `"<id>:<hash>"`, so the fallback
        // can't mis-identify the bytes it serves.
        if let bookId, !bookId.isEmpty, let base,
           let key = contentHashKey(identity: bookId, coverHash: coverHash),
           let url = coverURL(base: base, bookId: bookId, coverHash: coverHash) {
            return ImageRequest(url: url, processors: processors, userInfo: [.imageIdKey: key])
        }

        // No reachable content-addressed source: the durable local file the caller resolved is the
        // best source.
        if let coverPath, !coverPath.isEmpty {
            let cacheKey = localFileCacheKey(bookId: bookId, coverPath: coverPath, coverHash: coverHash)
            return AuthenticatedImageRequest.localFile(coverPath, processors: processors, cacheKey: cacheKey)
        }

        guard let bookId, !bookId.isEmpty, let base,
              let url = coverURL(base: base, bookId: bookId, coverHash: coverHash)
        else {
            return nil
        }

        // No hash → `nil` cache key → Nuke keys on the request URL (`/api/v1/covers/{bookId}`),
        // which is already unique per book. Never the `"bookId:cover"` custom key: it is the exact
        // poisoned key the old bug wrote, and during a switch (`coverPath == nil`, `bookId == B`)
        // it would let book A's still-cached bytes flash on book B. Passing `nil` also orphans any
        // stale `"<id>:cover"` disk entries. With a hash we keep the content-scoped `"<id>:<hash>"`.
        let userInfo: [ImageRequest.UserInfoKey: Any]? = contentHashKey(identity: bookId, coverHash: coverHash)
            .map { [.imageIdKey: $0] }
        return ImageRequest(url: url, processors: processors, userInfo: userInfo)
    }

    /// Whether a load was answered from Nuke's memory cache — the one case where the cover was never
    /// missing, so it shouldn't fade in.
    static func isMemoryCacheHit(_ result: Result<ImageResponse, Error>?) -> Bool {
        guard case .success(let response) = result else { return false }
        return response.cacheType == .memory
    }

    /// Whether a finished load should switch the cover to its downloaded file: a real load failure
    /// (offline with a cold cache, no token, a server error) when there IS a file to fall back to.
    /// `LazyImage` also reports "no request yet" as a failure while the view has no size; that one
    /// must not count, or the cover would never try the server.
    static func shouldFallBackToLocalFile(after result: Result<ImageResponse, Error>, coverPath: String?) -> Bool {
        guard case .failure(let error) = result, coverPath?.isEmpty == false else { return false }
        if case ImagePipeline.Error.imageRequestMissing = error { return false }
        return true
    }

    /// Whether a cover should be persisted for offline use: whenever its version is known (so a
    /// re-scrape re-fetches), or when there's no downloaded file yet. A hash-less cover that already
    /// has a file needs nothing. Mirrors the Compose `BookCoverImage` server fallback.
    static func shouldPersist(bookId: String?, coverPath: String?, coverHash: String?) -> Bool {
        guard let bookId, !bookId.isEmpty else { return false }
        if let coverHash, !coverHash.isEmpty { return true }
        return coverPath?.isEmpty ?? true
    }

    /// The authenticated book-cover endpoint for a server base URL. Pure, so the endpoint shape is
    /// unit-tested. Content-addresses the URL with the `coverHash` via a `?v=` query item so a
    /// re-covered book changes the URL itself — busting `URLSession`'s `URLCache` (keyed by URL), not
    /// just Nuke's cache key. The server ignores `?v`. A nil/blank hash yields the bare endpoint.
    static func coverURL(base: String, bookId: String, coverHash: String?) -> URL? {
        guard !base.isEmpty else { return nil }
        var components = URLComponents(string: "\(base)/api/v1/covers/\(bookId)")
        if let coverHash, !coverHash.isEmpty {
            components?.queryItems = [URLQueryItem(name: "v", value: coverHash)]
        }
        return components?.url
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
