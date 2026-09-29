import Foundation
import Nuke

/// The app's one Nuke pipeline, installed at launch before any image view appears.
///
/// Nuke's default pipeline caches on disk only through `URLCache`, which keys on the URL, honours the
/// server's cache headers and never stores the resized thumbnails every list actually shows
/// (2026-09-29 iOS audit, performance). This one:
/// - uses a `DataCache` keyed on each request's token-free image identity (`"<bookId>:<coverHash>"`
///   for covers), so a cover fetched once is there offline and across token rotation;
/// - stores both the original bytes and the processed thumbnails (`storeAll`), so a scroll back
///   through the library decodes a small thumbnail instead of re-resizing the original;
/// - loads through `AuthenticatingDataLoader`, which adds the access token for the connected server.
enum ListenUpImagePipeline {
    /// Big enough for a large library's thumbnails plus the originals behind the ones opened recently.
    static let diskCacheLimit = 300 * 1024 * 1024

    static func makeConfiguration(dataLoader: any DataLoading) -> ImagePipeline.Configuration {
        var configuration = ImagePipeline.Configuration.withDataCache(
            name: "audio.listenup.images",
            sizeLimit: diskCacheLimit
        )
        configuration.dataLoader = dataLoader
        configuration.dataCachePolicy = .storeAll
        return configuration
    }

    @MainActor
    static func install() {
        ImagePipeline.shared = ImagePipeline(
            configuration: makeConfiguration(dataLoader: AuthenticatingDataLoader.shared)
        )
    }
}
