import Foundation
import Testing
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

@Suite("PlayerObserver maps the shared state to native values")
@MainActor
struct PlayerObserverTests {
    @Test("nothing loaded hides the mini player")
    func nothingLoaded() {
        let snapshot = PlayerSnapshot.from(PlayerUiStateNothingLoaded.shared)
        #expect(snapshot.isVisible == false)
    }

    @Test("a loaded book becomes native rows with exactly one current chapter")
    func loaded() {
        let state = PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5)
        let snapshot = PlayerSnapshot.from(state)
        #expect(snapshot.isVisible)
        #expect(snapshot.currentBookId == "book-1")
        #expect(snapshot.bookTitle == "The Way of Kings")
        #expect(snapshot.authorName == "Brandon Sanderson")
        #expect(snapshot.seriesId == "series-1")
        #expect(snapshot.chapterRows.filter(\.isCurrent).map(\.index) == [5])
        #expect(snapshot.chapterIndex == 5)
        #expect(snapshot.totalChapters == 8)
        #expect(snapshot.chapterTitle == "Chapter 6")
        #expect(snapshot.isPlaying)
        #expect(snapshot.showsSkipBack)
        #expect(snapshot.primary == .pause)
        #expect(snapshot.playbackSpeed == 1.5)
    }

    @Test("a failed book stays visible with its typed title and the Retry primary")
    func failed() {
        let error = PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)
        let snapshot = PlayerSnapshot.from(
            PlayerUiStateLoaded.fixture(phase: PlayerPhaseFailed(error: error), primary: PrimaryControlRetry.shared)
        )
        #expect(snapshot.isVisible)
        #expect(snapshot.isErrored)
        #expect(snapshot.errorCode == "PLAYBACK_CONNECTION_LOST")
        #expect(snapshot.errorMessage?.isEmpty == false)
        #expect(snapshot.errorMessage != "player.title_playback_connection_lost")
        #expect(snapshot.primary == .retry)
        #expect(!snapshot.isPlaying)
    }

    @Test("a book reconnecting after a network drop shows the buffering presentation, not an error")
    func reconnecting() {
        let snapshot = PlayerSnapshot.from(
            PlayerUiStateLoaded.fixture(phase: PlayerPhaseReconnecting.shared, primary: PrimaryControlBusy.shared)
        )
        #expect(snapshot.isBuffering)
        #expect(!snapshot.isPlaying)
        #expect(!snapshot.isErrored)
        #expect(snapshot.primary == .busy)
        #expect(snapshot.miniLine == .reconnecting)
    }

    @Test("a known reason the book can't play reads as an error with its reason")
    func unavailable() {
        let reason = PlaybackErrorOfflineNotDownloaded(correlationId: nil, debugInfo: nil)
        let state = PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseUnavailable(reason: reason), primary: PrimaryControlRetry.shared)
        let snapshot = PlayerSnapshot.from(state)
        #expect(snapshot.isErrored)
        #expect(snapshot.errorCode == "PLAYBACK_OFFLINE_NOT_DOWNLOADED")
    }

    @Test("a chapterless book reads as one whole-book chapter")
    func chapterless() {
        let snapshot = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(wholeBook: true))
        #expect(snapshot.totalChapters == 1)
        #expect(snapshot.chapterTitle == nil)
    }

    @Test("a running sleep timer shows its remaining time; end of chapter is flagged")
    func sleepTimer() {
        let countdown = SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 15), remainingMs: 65_000, totalMs: 900_000, startedAt: 0)
        let running = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(sleepTimer: countdown))
        #expect(running.sleepTimerActive)
        #expect(!running.sleepTimerIsEndOfChapter)
        #expect(running.sleepTimerLabel == "1:05")

        let endOfChapter = SleepTimerStateActive(
            mode: SleepTimerModeEndOfChapter.shared, remainingMs: 0, totalMs: 0, startedAt: 0)
        #expect(PlayerSnapshot.from(PlayerUiStateLoaded.fixture(sleepTimer: endOfChapter)).sleepTimerIsEndOfChapter)
    }

    @Test("the mini player's line arrives as a native value, named by the book's own chapter title")
    func miniLine() {
        let playing = PlayerSnapshot.from(
            PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Bridge Four"))
        #expect(playing.miniLine == .chapter(number: nil, title: "Bridge Four", sleepRemainingMs: nil))

        let untitled = PlayerSnapshot.from(
            PlayerUiStateLoaded.fixture(phase: PlayerPhasePaused.shared, currentIndex: 5, chapterTitle: ""))
        #expect(untitled.miniLine == .paused(number: 6, title: nil, sleepRemainingMs: nil))

        let wholeBook = PlayerSnapshot.from(
            PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, wholeBook: true))
        #expect(wholeBook.miniLine == .chapter(number: nil, title: nil, sleepRemainingMs: nil))

        let failed = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared))
        guard case .problem(let code, let title, let isTinted) = failed.miniLine else {
            Issue.record("expected a problem line, got \(failed.miniLine)")
            return
        }
        #expect(code == "PLAYBACK_CONNECTION_LOST")
        #expect(title == "Couldn't play this book.")
        #expect(isTinted)
    }

    @Test("the status words, the counter rule and the book percent")
    func nowPlayingFacts() {
        let snapshot = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, source: AudioSourceStreamingTranscoded.shared))
        #expect(snapshot.statusText == String(localized: "player.np_status_streaming_transcoded"))
        #expect(!snapshot.isCasting)
        #expect(snapshot.showsChapterCounter)
        #expect(!PlayerSnapshot.from(PlayerUiStateLoaded.fixture(isGenericTitle: true)).showsChapterCounter)
        #expect(!PlayerSnapshot.from(PlayerUiStateLoaded.fixture(wholeBook: true)).showsChapterCounter)
        #expect(PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 0, chapterDurationMs: 0, bookPositionMs: 18_000_000, bookDurationMs: 163_800_000
        )).bookPercent == 10)
    }

    @Test("casting reads Playing on the device, in the tint")
    func casting() {
        let snapshot = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, source: AudioSourceCasting(deviceName: "Living Room")))
        #expect(snapshot.statusText == String(format: String(localized: "player.np_status_playing_on"), "Living Room"))
        #expect(snapshot.isCasting)
    }

    @Test("progress becomes a native row with its derived values")
    func progress() {
        let row = PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 30_000, chapterDurationMs: 120_000, bookPositionMs: 330_000, bookDurationMs: 600_000))
        #expect(row.chapterRemainingMs == 90_000)
        #expect(row.bookRemainingMs == 270_000)
        #expect(row.bookFraction == 0.55)
    }
}

extension PlayerUiStateLoaded {
    /// A loaded book in the shape the shared view model emits, spelled with the Swift Export initializers.
    static func fixture(
        phase: any PlayerPhase = PlayerPhasePaused.shared,
        currentIndex: Int = 0,
        wholeBook: Bool = false,
        primary: any PrimaryControl = PrimaryControlPause.shared,
        sleepTimer: any SleepTimerState = SleepTimerStateInactive.shared,
        chapterTitle: String? = nil,
        source: any AudioSource = AudioSourceStreaming.shared,
        showsSkipBack: Bool = true,
        finished: FinishedInfo? = nil,
        firstPdfDocId: String? = nil,
        boostDb: Float = 0,
        isGenericTitle: Bool = false,
        narrators: [PlayerContributor] = [PlayerContributor(id: "n1", name: "Michael Kramer", bookCount: 2)],
        chapterCount: Int = 8,
        chapterDurationMs: Int64 = 60_000
    ) -> PlayerUiStateLoaded {
        let total = wholeBook ? 1 : chapterCount
        let bookMs = Int64(chapterCount) * chapterDurationMs
        let rows = wholeBook ? [] : (0..<total).map { index in
            PlayerChapterRow(
                index: Int32(index), number: Int32(index + 1), title: "Chapter \(index + 1)",
                startMs: Int64(index) * chapterDurationMs, durationMs: chapterDurationMs, isCurrent: index == currentIndex)
        }
        return PlayerUiStateLoaded(
            book: PlayerBook(
                id: "book-1", title: "The Way of Kings",
                authors: [PlayerContributor(id: "a1", name: "Brandon Sanderson", bookCount: 3)],
                narrators: narrators,
                coverPath: nil, coverHash: nil,
                series: [PlayerSeriesRef(id: "series-1", name: "The Stormlight Archive", sequenceLabel: "1")],
                durationMs: bookMs, firstPdfDocId: firstPdfDocId),
            phase: phase,
            chapter: PlayerChapter(
                index: Int32(wholeBook ? 0 : currentIndex), number: Int32((wholeBook ? 0 : currentIndex) + 1),
                total: Int32(total), title: wholeBook ? "" : (chapterTitle ?? "Chapter \(currentIndex + 1)"),
                isGenericTitle: wholeBook || isGenericTitle, isWholeBook: wholeBook,
                startMs: wholeBook ? 0 : Int64(currentIndex) * chapterDurationMs,
                durationMs: wholeBook ? bookMs : chapterDurationMs),
            chapters: rows,
            source: source,
            settings: PlayerSettings(
                speed: 1.5, defaultSpeed: 1.0, boostDb: boostDb, defaultBoostDb: 0, skipBackSec: 10,
                skipForwardSec: 30),
            sleepTimer: sleepTimer,
            primary: primary,
            showsSkipBack: showsSkipBack,
            canSkipToNextChapter: !wholeBook && currentIndex < total - 1,
            finished: finished
        )
    }
}
