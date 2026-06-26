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
    static func book(bookId: String?, coverPath: String?, targetPixels: CGFloat) async -> ImageRequest? {
        let processors = AuthenticatedImageRequest.processors(targetPixels: targetPixels)

        if let coverPath, !coverPath.isEmpty {
            return AuthenticatedImageRequest.localFile(coverPath, processors: processors)
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

        return await AuthenticatedImageRequest.authenticated(url: url, processors: processors)
    }
}
