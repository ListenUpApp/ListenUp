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
        let processors: [any ImageProcessing] =
            targetPixels > 0 ? [ImageProcessors.Resize(width: targetPixels, unit: .pixels)] : []

        if let coverPath, !coverPath.isEmpty {
            return ImageRequest(url: URL(fileURLWithPath: coverPath), processors: processors)
        }

        guard let bookId, !bookId.isEmpty else { return nil }

        let deps = Dependencies.shared
        // SKIE unboxes the Kotlin `ServerUrl`/`AccessToken` value classes to their
        // underlying `String`, bridged as `Any?` — hence the `as? String` cast.
        guard let base = (try? await deps.serverConfig.getActiveUrl()) as? String, !base.isEmpty,
              let url = URL(string: "\(base)/api/v1/covers/\(bookId)")
        else { return nil }

        var urlRequest = URLRequest(url: url)
        if let token = (try? await deps.authSession.getAccessToken()) as? String {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return ImageRequest(urlRequest: urlRequest, processors: processors)
    }
}
