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
        sleepTimer: any SleepTimerState = SleepTimerStateInactive.shared
    ) -> PlayerUiStateLoaded {
        let total = wholeBook ? 1 : 8
        let rows = wholeBook ? [] : (0..<total).map { index in
            PlayerChapterRow(
                index: Int32(index), number: Int32(index + 1), title: "Chapter \(index + 1)",
                startMs: Int64(index) * 60_000, durationMs: 60_000, isCurrent: index == currentIndex)
        }
        return PlayerUiStateLoaded(
            book: PlayerBook(
                id: "book-1", title: "The Way of Kings",
                authors: [PlayerContributor(id: "a1", name: "Brandon Sanderson", bookCount: 3)],
                narrators: [PlayerContributor(id: "n1", name: "Michael Kramer", bookCount: 2)],
                coverPath: nil, coverHash: nil,
                series: [PlayerSeriesRef(id: "series-1", name: "The Stormlight Archive", sequenceLabel: "1")],
                durationMs: 480_000, firstPdfDocId: nil),
            phase: phase,
            chapter: PlayerChapter(
                index: Int32(wholeBook ? 0 : currentIndex), number: Int32((wholeBook ? 0 : currentIndex) + 1),
                total: Int32(total), title: wholeBook ? "" : "Chapter \(currentIndex + 1)",
                isGenericTitle: false, isWholeBook: wholeBook,
                startMs: wholeBook ? 0 : Int64(currentIndex) * 60_000, durationMs: wholeBook ? 480_000 : 60_000),
            chapters: rows,
            source: AudioSourceStreaming.shared,
            settings: PlayerSettings(
                speed: 1.5, defaultSpeed: 1.0, boostDb: 0, defaultBoostDb: 0, skipBackSec: 10, skipForwardSec: 30),
            sleepTimer: sleepTimer,
            primary: primary,
            showsSkipBack: true,
            canSkipToNextChapter: !wholeBook && currentIndex < total - 1,
            finished: nil
        )
    }
}
