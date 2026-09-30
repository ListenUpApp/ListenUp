import Foundation
import Nuke
import Testing
@testable import ListenUp

/// Pins the pipeline shape: one on-disk data cache that keeps the resized thumbnails as well as the
/// originals, loaded through the authenticating loader.
@Suite("ListenUpImagePipeline")
struct ListenUpImagePipelineTests {
    @Test func storesOriginalsAndThumbnailsOnDisk() {
        let configuration = ListenUpImagePipeline.makeConfiguration(dataLoader: AuthenticatingDataLoader.shared)
        #expect(configuration.dataCachePolicy == .storeAll)
        #expect(configuration.dataCache != nil)
    }

    @Test func loadsThroughTheAuthenticatingLoader() {
        let configuration = ListenUpImagePipeline.makeConfiguration(dataLoader: AuthenticatingDataLoader.shared)
        #expect(configuration.dataLoader is AuthenticatingDataLoader)
    }
}
