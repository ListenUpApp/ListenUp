import MediaPlayer
import Testing
@testable import ListenUp

@Suite("Now Playing artwork")
struct SystemIntegrationArtworkTests {
    @Test func dictionaryOmitsArtworkWhenNoPath() {
        let info = NowPlayingInfo(title: "T", artist: "A", durationMs: 1000, elapsedMs: 0, rate: 1, artworkPath: nil)
        let dict = SystemIntegration.dictionary(from: info)
        #expect(dict[MPMediaItemPropertyArtwork] == nil)
    }
    @Test func dictionaryIncludesArtworkWhenPathExists() {
        let url = Bundle(for: BundleMarker.self).url(forResource: "test_cover", withExtension: "png")!
        let info = NowPlayingInfo(
            title: "T", artist: "A", durationMs: 1000, elapsedMs: 0, rate: 1, artworkPath: url.path)
        let dict = SystemIntegration.dictionary(from: info)
        #expect(dict[MPMediaItemPropertyArtwork] is MPMediaItemArtwork)
    }

    @Test func artworkIsCachedAcrossPushesForTheSamePath() {
        let url = Bundle(for: BundleMarker.self).url(forResource: "test_cover", withExtension: "png")!
        let info = NowPlayingInfo(
            title: "T", artist: "A", durationMs: 1000, elapsedMs: 0, rate: 1, artworkPath: url.path)
        let first = SystemIntegration.dictionary(from: info)[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork
        let second = SystemIntegration.dictionary(from: info)[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork
        #expect(first != nil)
        #expect(first === second)
    }

    @Test func artworkIsDownsampledWithinTheCap() throws {
        let url = Bundle(for: BundleMarker.self).url(forResource: "test_cover", withExtension: "png")!
        let artwork = try #require(SystemIntegration.artwork(forPath: url.path))
        #expect(max(artwork.bounds.size.width, artwork.bounds.size.height) <= 1024)
    }
}

final class BundleMarker {}
