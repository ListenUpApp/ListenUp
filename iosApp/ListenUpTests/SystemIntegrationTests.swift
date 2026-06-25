import Foundation
import MediaPlayer
import Testing
@testable import ListenUp

@Suite("SystemIntegration.dictionary")
struct SystemIntegrationTests {
    private let info = NowPlayingInfo(
        title: "The Way of Kings",
        artist: "Brandon Sanderson",
        durationMs: 3_600_000,
        elapsedMs: 600_000,
        rate: 1.5,
        artworkPath: nil
    )

    @Test func mapsTitleAndArtist() {
        let dict = SystemIntegration.dictionary(from: info)
        #expect(dict[MPMediaItemPropertyTitle] as? String == "The Way of Kings")
        #expect(dict[MPMediaItemPropertyArtist] as? String == "Brandon Sanderson")
    }

    @Test func convertsMillisecondsToSeconds() {
        let dict = SystemIntegration.dictionary(from: info)
        #expect(dict[MPMediaItemPropertyPlaybackDuration] as? Double == 3600.0)
        #expect(dict[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double == 600.0)
    }

    @Test func carriesRateForClockExtrapolation() {
        let dict = SystemIntegration.dictionary(from: info)
        #expect(dict[MPNowPlayingInfoPropertyPlaybackRate] as? Double == 1.5)
    }
}
