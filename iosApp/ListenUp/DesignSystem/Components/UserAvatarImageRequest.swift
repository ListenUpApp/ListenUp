import Foundation
import Nuke
@preconcurrency import Shared

/// Builds Nuke image requests for user avatars: the content-addressed authenticated server URL
/// `{activeUrl}/api/v1/avatars/{userId}?v={version}`, else the durable local file.
///
/// Mirrors `ContributorImageRequest`. The avatar's `version` (the `public_profiles` row's
/// `updatedAt`) is the content marker: folding it into both the Nuke cache key AND the URL's `?v=`
/// means a re-uploaded avatar (new version) re-fetches instead of serving the stale id-stable local
/// file. That local file is keyed only by user id, so after a re-upload it can hold STALE bytes until
/// an unreliable delete lands — the race that left avatars stale. `ensureUserAvatarCached` keeps the
/// durable file fresh in the background for offline/other consumers.
enum UserAvatarImageRequest {
    @MainActor
    static func avatar(
        userId: String,
        version: Int64,
        targetPixels: CGFloat
    ) async -> ImageRequest? {
        let processors = AuthenticatedImageRequest.processors(targetPixels: targetPixels)
        let key = cacheKey(userId: userId, version: version)

        let repository = KoinHelper.shared.getImageRepository()

        // Content-addressed server URL when the version is known — NOT the id-stable local file.
        if version > 0 {
            repository.ensureUserAvatarCached(userId: userId)
            let base = try? await KoinHelper.shared.activeServerUrl()
            if let base, let url = avatarURL(base: base, userId: userId, version: version) {
                return await AuthenticatedImageRequest.authenticated(url: url, processors: processors, cacheKey: key)
            }
        }

        // No known version (or no server URL): the durable local file is the best source.
        if repository.userAvatarExists(userId: userId) {
            let path = repository.getUserAvatarPath(userId: userId)
            return AuthenticatedImageRequest.localFile(path, processors: processors, cacheKey: key)
        }

        // Nothing cached and no version — stream by id so the avatar isn't blank when online.
        repository.ensureUserAvatarCached(userId: userId)
        let fallbackBase = try? await KoinHelper.shared.activeServerUrl()
        guard let base = fallbackBase, let url = avatarURL(base: base, userId: userId, version: 0) else {
            return nil
        }
        return await AuthenticatedImageRequest.authenticated(url: url, processors: processors, cacheKey: key)
    }

    /// Content-scoped Nuke cache key `"<userId>:<version>"`, or `"<userId>:avatar"` when no version.
    /// Folding the version busts the cached bitmap on a re-upload; token-independent, so a cached
    /// avatar survives access-token rotation. Pure, so it's unit-testable.
    static func cacheKey(userId: String, version: Int64) -> String {
        "\(userId):\(version > 0 ? String(version) : "avatar")"
    }

    /// The authenticated user-avatar endpoint for a server base URL. Content-addresses the URL with
    /// the avatar `version` via a `?v=` query item so a re-upload changes the URL itself — busting
    /// `URLSession`'s `URLCache` (keyed by URL), not just Nuke's cache key. The server ignores `?v`.
    /// A zero/absent version yields the bare endpoint. Pure, so the shape is unit-testable.
    static func avatarURL(base: String, userId: String, version: Int64) -> URL? {
        guard !base.isEmpty else { return nil }
        var components = URLComponents(string: "\(base)/api/v1/avatars/\(userId)")
        if version > 0 {
            components?.queryItems = [URLQueryItem(name: "v", value: String(version))]
        }
        return components?.url
    }
}
