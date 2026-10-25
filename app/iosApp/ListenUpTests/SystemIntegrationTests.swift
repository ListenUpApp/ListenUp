import AVFoundation
import Foundation
import MediaPlayer
import Shared
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

    /// The lock screen and CarPlay's rate button both read the rates the command advertises, so
    /// they must be the app's own catalogue — and the command must be live, not merely declared.
    @Test func attachAdvertisesTheCatalogueOnTheRateCommand() {
        let session = MPNowPlayingSession(players: [AVPlayer()])
        let system = SystemIntegration(session: session)
        let handler = RecordingRemoteHandler()

        system.attach(handler: handler)

        let command = session.remoteCommandCenter.changePlaybackRateCommand
        #expect(command.isEnabled)
        #expect(command.supportedPlaybackRates.map(\.floatValue) == PlaybackRates.catalogue)
    }

    /// Previous and next track are the chapter (§3.3, gap 25), for AirPods, CarPlay and headsets: registered and live.
    @Test func attachRegistersPreviousAndNextTrack() {
        let session = MPNowPlayingSession(players: [AVPlayer()])
        let system = SystemIntegration(session: session)

        system.attach(handler: RecordingRemoteHandler())

        #expect(session.remoteCommandCenter.previousTrackCommand.isEnabled)
        #expect(session.remoteCommandCenter.nextTrackCommand.isEnabled)
        // The interval skips stay registered beside them.
        system.updateSkipIntervals(forwardSeconds: 30, backwardSeconds: 10)
        #expect(session.remoteCommandCenter.skipForwardCommand.preferredIntervals == [30])
        #expect(session.remoteCommandCenter.skipBackwardCommand.preferredIntervals == [10])
    }

    /// At the last chapter next track greys out; previous never does (it restarts the chapter).
    @Test func nextTrackGreysOutAtTheLastChapter() {
        let session = MPNowPlayingSession(players: [AVPlayer()])
        let system = SystemIntegration(session: session)
        system.attach(handler: RecordingRemoteHandler())
        var info = NowPlayingInfo(
            title: "The Way of Kings", artist: "Epilogue",
            windowDurationMs: 1_000, windowElapsedMs: 0, rate: 1.0,
            artworkPath: nil, chapterNumber: 75, chapterCount: 75
        )
        info.hasNextChapter = false

        system.update(info)

        #expect(!session.remoteCommandCenter.nextTrackCommand.isEnabled)
        #expect(session.remoteCommandCenter.previousTrackCommand.isEnabled)

        info.hasNextChapter = true
        system.update(info)
        #expect(session.remoteCommandCenter.nextTrackCommand.isEnabled)
    }

    /// A chapterless book has no previous track: it greys out rather than jump to 0:00.
    @Test func previousTrackGreysOutInAChapterlessBook() {
        let session = MPNowPlayingSession(players: [AVPlayer()])
        let system = SystemIntegration(session: session)
        system.attach(handler: RecordingRemoteHandler())
        var info = NowPlayingInfo.chapterScoped(
            title: "The Way of Kings", artist: "Whole book", bookPositionMs: 5_000, chapters: [],
            bookDurationMs: 60_000, rate: 1.0, artworkPath: nil
        )
        #expect(!info.hasPreviousChapter)

        system.update(info)
        #expect(!session.remoteCommandCenter.previousTrackCommand.isEnabled)

        info = NowPlayingInfo.chapterScoped(
            title: "The Way of Kings", artist: "Prelude", bookPositionMs: 5_000,
            chapters: [Chapter(id: "c1", title: "Prelude", duration: 60_000, startTime: 0, partTitle: nil, bookTitle: nil)],
            bookDurationMs: 60_000, rate: 1.0, artworkPath: nil
        )
        #expect(info.hasPreviousChapter)
        system.update(info)
        #expect(session.remoteCommandCenter.previousTrackCommand.isEnabled)
    }

    @Test func clearEmptiesTheSessionsInfoCenter() {
        let session = MPNowPlayingSession(players: [AVPlayer()])
        let system = SystemIntegration(session: session)
        session.nowPlayingInfoCenter.nowPlayingInfo = [MPMediaItemPropertyTitle: "stale"]

        system.clear()

        #expect(session.nowPlayingInfoCenter.nowPlayingInfo == nil)
    }
}

/// Records the remote commands a `SystemIntegration` forwards.
@MainActor
final class RecordingRemoteHandler: RemoteCommandHandler {
    private(set) var requestedRates: [Float] = []
    func remoteTogglePlayPause() {}
    func remotePlay() {}
    func remotePause() {}
    func remoteSkipForward() {}
    func remoteSkipBackward() {}
    func remotePreviousTrack() -> Bool { true }
    func remoteNextTrack() -> Bool { true }
    func remoteSeek(toWindowPositionMs positionMs: Int64) {}
    func remoteSetRate(_ rate: Float) { requestedRates.append(rate) }
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

    /// Spec §5.7: title = the book, artist = the chapter, album = the author.
    @Test func theAuthorRidesInTheAlbum() {
        var named = NowPlayingInfo(
            title: "The Way of Kings", artist: "Bridge Four",
            windowDurationMs: 1_000, windowElapsedMs: 0, rate: 1.0,
            artworkPath: nil, chapterNumber: 6, chapterCount: 75
        )
        named.album = "Brandon Sanderson"
        let dict = SystemIntegration.dictionary(from: named)
        #expect(dict[MPMediaItemPropertyTitle] as? String == "The Way of Kings")
        #expect(dict[MPMediaItemPropertyArtist] as? String == "Bridge Four")
        #expect(dict[MPMediaItemPropertyAlbumTitle] as? String == "Brandon Sanderson")
    }

    /// The chapter is named as the mini player names it: its own title, else its number, else the whole book.
    @Test func theChapterIsNamedByTheSharedRule() {
        #expect(NowPlayingInfo.chapterName(title: "Bridge Four", number: 6, isWholeBook: false) == "Bridge Four")
        #expect(NowPlayingInfo.chapterName(title: "Chapter 14", number: 16, isWholeBook: false) == "Chapter 14")
        #expect(NowPlayingInfo.chapterName(title: " ", number: 6, isWholeBook: false)
            == String(format: String(localized: "player.mini_chapter"), Int32(6)))
        #expect(NowPlayingInfo.chapterName(title: nil, number: 1, isWholeBook: true)
            == String(localized: "player.mini_whole_book"))
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

    /// While paused the live rate is 0, so the chosen speed rides in the default-rate key — it is
    /// what the lock screen and CarPlay's rate button show as the current speed.
    @Test func carriesTheChosenSpeedAsTheDefaultRate() {
        var paused = info
        paused.defaultRate = 1.75
        let dict = SystemIntegration.dictionary(from: paused)
        #expect(dict[MPNowPlayingInfoPropertyDefaultPlaybackRate] as? Double == 1.75)
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
