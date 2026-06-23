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
    static func contributor(contributorId: String, targetPixels: CGFloat) async -> ImageRequest? {
        let processors: [any ImageProcessing] =
            targetPixels > 0 ? [ImageProcessors.Resize(width: targetPixels, unit: .pixels)] : []

        let repository = KoinHelper.shared.getImageRepository()

        // Offline fast path: a previously cached photo on disk.
        if repository.contributorImageExists(contributorId: contributorId) {
            let path = repository.getContributorImagePath(contributorId: contributorId)
            return ImageRequest(url: URL(fileURLWithPath: path), processors: processors)
        }

        // No durable file yet — persist this streamed photo on disk for offline use (fire-and-forget,
        // no-op once it exists), then stream from the server now.
        repository.ensureContributorImageCached(contributorId: contributorId)

        guard let base = try? await KoinHelper.shared.activeServerUrl(),
              let url = photoURL(base: base, contributorId: contributorId)
        else { return nil }

        var urlRequest = URLRequest(url: url)
        if let token = try? await KoinHelper.shared.accessToken() {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return ImageRequest(urlRequest: urlRequest, processors: processors)
    }

    /// The authenticated contributor-photo endpoint for a server base URL. Pure, so the endpoint
    /// shape is unit-tested. Returns nil for a blank base.
    static func photoURL(base: String, contributorId: String) -> URL? {
        guard !base.isEmpty else { return nil }
        return URL(string: "\(base)/api/v1/contributors/\(contributorId)/photo")
    }
}
