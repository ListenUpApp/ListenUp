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
        let info = NowPlayingInfo(title: "T", artist: "A", durationMs: 1000, elapsedMs: 0, rate: 1, artworkPath: url.path)
        let dict = SystemIntegration.dictionary(from: info)
        #expect(dict[MPMediaItemPropertyArtwork] is MPMediaItemArtwork)
    }
}

final class BundleMarker {}
