import Testing
import UIKit
@testable import ListenUp

@Suite("ImageDownsampler")
struct ImageDownsamplerTests {
    private var coverPath: String {
        Bundle(for: BundleMarker.self).url(forResource: "test_cover", withExtension: "png")!.path
    }

    @Test func downsamplesToWithinMaxPixelSize() throws {
        let image = try #require(ImageDownsampler.downsampledImage(atPath: coverPath, maxPixelSize: 64))
        // UIImage(cgImage:) has scale 1, so size is in pixels.
        #expect(max(image.size.width, image.size.height) <= 64)
    }

    @Test func returnsNilForZeroMaxPixelSize() {
        #expect(ImageDownsampler.downsampledImage(atPath: coverPath, maxPixelSize: 0) == nil)
    }

    @Test func returnsNilForMissingFile() {
        #expect(ImageDownsampler.downsampledImage(atPath: "/no/such/file.png", maxPixelSize: 64) == nil)
    }
}
