import AVFoundation
import Foundation
import MediaPlayer
import Testing
@testable import ListenUp

/// When a `MPNowPlayingSession` is provided, every push must land in the SESSION's info center,
/// not the process-global one: an active session is what makes the system derive play/pause and
/// timeline state from the attached player, and publishing to `.default()` alongside it splits
/// the now-playing state across two stores (CarPlay reads one, the lock screen the other).
@MainActor
@Suite("SystemIntegration session publishing")
struct SystemIntegrationSessionTests {
    @Test func updatePushesToTheSessionsInfoCenter() {
        let session = MPNowPlayingSession(players: [AVPlayer()])
        let system = SystemIntegration(session: session)
        let info = NowPlayingInfo(
            title: "The Way of Kings", artist: "Brandon Sanderson",
            windowDurationMs: 1_000, windowElapsedMs: 0, rate: 1.0,
            artworkPath: nil, chapterNumber: nil, chapterCount: nil
        )

        system.update(info)

        let pushed = session.nowPlayingInfoCenter.nowPlayingInfo
        #expect(pushed?[MPMediaItemPropertyTitle] as? String == "The Way of Kings")
    }

    @Test func clearEmptiesTheSessionsInfoCenter() {
        let session = MPNowPlayingSession(players: [AVPlayer()])
        let system = SystemIntegration(session: session)
        session.nowPlayingInfoCenter.nowPlayingInfo = [MPMediaItemPropertyTitle: "stale"]

        system.clear()

        #expect(session.nowPlayingInfoCenter.nowPlayingInfo == nil)
    }
}

@Suite("SystemIntegration.dictionary")
struct SystemIntegrationTests {
    private let info = NowPlayingInfo(
        title: "The Way of Kings",
        artist: "Brandon Sanderson",
        windowDurationMs: 3_600_000,
        windowElapsedMs: 600_000,
        rate: 1.5,
        artworkPath: nil,
        chapterNumber: 3,
        chapterCount: 92
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

    /// The system renders "3 of 92" from these; a CarPlay now-playing template reads the same
    /// keys, which is why they land here rather than being folded into the title string.
    @Test func carriesChapterNumberAndCount() {
        let dict = SystemIntegration.dictionary(from: info)
        #expect(dict[MPNowPlayingInfoPropertyChapterNumber] as? Int == 3)
        #expect(dict[MPNowPlayingInfoPropertyChapterCount] as? Int == 92)
    }

    /// A chapterless book presents as one whole-book window, so there is no chapter to number.
    /// Publishing 0-of-0 would render as a real (wrong) chapter position, so the keys are omitted.
    @Test func omitsChapterKeysForAChapterlessBook() {
        let chapterless = NowPlayingInfo(
            title: "A Book",
            artist: "An Author",
            windowDurationMs: 3_600_000,
            windowElapsedMs: 600_000,
            rate: 1.0,
            artworkPath: nil,
            chapterNumber: nil,
            chapterCount: nil
        )
        let dict = SystemIntegration.dictionary(from: chapterless)
        #expect(dict[MPNowPlayingInfoPropertyChapterNumber] == nil)
        #expect(dict[MPNowPlayingInfoPropertyChapterCount] == nil)
    }
}
