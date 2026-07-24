import Nuke
import Foundation
import Testing
@testable import ListenUp

/// Pins the pure surface of the shared image-request core that `CoverImageRequest` and
/// `ContributorImageRequest` both route through: the resize-processor choice and the local-file
/// request shape. The authenticated server path needs Koin/network and is left to the manual smoke.
@Suite("AuthenticatedImageRequest")
struct AuthenticatedImageRequestTests {
    @Test @MainActor func noProcessorsForNonPositiveTarget() {
        #expect(AuthenticatedImageRequest.processors(targetPixels: 0).isEmpty)
        #expect(AuthenticatedImageRequest.processors(targetPixels: -10).isEmpty)
    }

    @Test @MainActor func oneResizeProcessorForPositiveTarget() {
        let processors = AuthenticatedImageRequest.processors(targetPixels: 240)
        #expect(processors.count == 1)
        #expect(processors.first is ImageProcessors.Resize)
    }

    @Test func localFileRequestPointsAtTheFilePath() {
        let request = AuthenticatedImageRequest.localFile("/var/covers/book-1.jpg", processors: [])
        #expect(request.url == URL(fileURLWithPath: "/var/covers/book-1.jpg"))
    }

    @Test func localFileRequestCarriesOverrideCacheKey() {
        let request = AuthenticatedImageRequest.localFile(
            "/var/contributors/c-1.jpg", processors: [], cacheKey: "c-1:contributors/aaa.jpg"
        )
        #expect(request.userInfo[.imageIdKey] as? String == "c-1:contributors/aaa.jpg")
    }

    @Test func localFileRequestWithoutCacheKeyLeavesDefault() {
        let request = AuthenticatedImageRequest.localFile("/var/covers/book-1.jpg", processors: [])
        #expect(request.userInfo[.imageIdKey] == nil)
    }
}
