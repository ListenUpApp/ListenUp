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
        #expect(running.sleepTimerTotalMs == 900_000)
        #expect(running.sleepTimerCanExtend)
        #expect(PlayerObserver.detached(PlayerUiStateLoaded.fixture(sleepTimer: countdown)).sleepTimerLabel == "1:05")

        let endOfChapter = SleepTimerStateActive(
            mode: SleepTimerModeEndOfChapter.shared, remainingMs: 0, totalMs: 0, startedAt: 0)
        let chapterEnd = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(sleepTimer: endOfChapter))
        #expect(chapterEnd.sleepTimerIsEndOfChapter)
        #expect(chapterEnd.sleepTimerTotalMs == 0)
        #expect(!chapterEnd.sleepTimerCanExtend)
    }

    @Test("an end-of-chapter timer counts down the chapter's remainder in wall time, not 0:00")
    func endOfChapterCountdown() {
        let endOfChapter = SleepTimerStateActive(
            mode: SleepTimerModeEndOfChapter.shared, remainingMs: 0, totalMs: 0, startedAt: 0)
        let progress = PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 720_000, chapterDurationMs: 1_200_000, bookPositionMs: 720_000,
            bookDurationMs: 9_600_000))
        let atOne = PlayerObserver.detached(
            PlayerUiStateLoaded.fixture(sleepTimer: endOfChapter, speed: 1.0), progress: progress)
        #expect(atOne.sleepCountdownMs == 480_000)
        #expect(atOne.sleepTimerLabel == "8:00")
        let atTwo = PlayerObserver.detached(
            PlayerUiStateLoaded.fixture(sleepTimer: endOfChapter, speed: 2.0), progress: progress)
        #expect(atTwo.sleepCountdownMs == 240_000)
        #expect(PlayerObserver.detached(PlayerUiStateLoaded.fixture(), progress: progress).sleepCountdownMs == nil)
    }

    @Test("the sheets' facts: the default speed, chapter numbers, contributor counts, End of chapter, the fade")
    func sheetFacts() {
        let snapshot = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseSleepFading.shared, currentIndex: 2, speed: 1.25, defaultSpeed: 1.1,
            numberOffset: 3))
        #expect(snapshot.defaultSpeed == 1.1)
        #expect(snapshot.playbackSpeed == 1.25)
        #expect(snapshot.chapterRows.map(\.number).prefix(3) == [4, 5, 6])
        #expect(snapshot.chapterRows.map(\.index).prefix(3) == [0, 1, 2])
        #expect(snapshot.narrators.map(\.bookCount) == [2])
        #expect(snapshot.authors.map(\.bookCount) == [3])
        #expect(snapshot.offersEndOfChapter)
        #expect(snapshot.isSleepFading)
        #expect(!PlayerSnapshot.from(PlayerUiStateLoaded.fixture(wholeBook: true)).offersEndOfChapter)
        #expect(!PlayerSnapshot.from(PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared)).isSleepFading)
    }

    @Test("the mini player's line arrives as a native value, named by the book's own chapter title")
    func miniLine() {
        let playing = PlayerSnapshot.from(
            PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Bridge Four"))
        #expect(playing.miniLine == .chapter(number: nil, title: "Bridge Four", sleepArmed: false))

        let untitled = PlayerSnapshot.from(
            PlayerUiStateLoaded.fixture(phase: PlayerPhasePaused.shared, currentIndex: 5, chapterTitle: ""))
        #expect(untitled.miniLine == .paused(number: 6, title: nil, sleepArmed: false))

        let wholeBook = PlayerSnapshot.from(
            PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, wholeBook: true))
        #expect(wholeBook.miniLine == .chapter(number: nil, title: nil, sleepArmed: false))

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
        #expect(snapshot.castDeviceName == "Living Room")
        #expect(PlayerSnapshot.from(PlayerUiStateLoaded.fixture()).castDeviceName == nil)
    }

    @Test("an armed timer reaches the mini line as armed, and the line's number is the observer's")
    func miniLineSleepArmed() {
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeEndOfChapter.shared, remainingMs: 0, totalMs: 0, startedAt: 0)
        let snapshot = PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer, chapterTitle: "Bridge Four"))
        #expect(snapshot.miniLine == .chapter(number: nil, title: "Bridge Four", sleepArmed: true))
    }

    @Test("progress becomes a native row with its derived values")
    func progress() {
        let row = PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 30_000, chapterDurationMs: 120_000, bookPositionMs: 330_000, bookDurationMs: 600_000))
        #expect(row.chapterRemainingMs == 90_000)
        #expect(row.bookRemainingMs == 270_000)
        #expect(row.bookFraction == 0.55)
    }

    @Test("the chapter's fraction feeds the chapter panel's row bar, and an empty chapter is at zero")
    func chapterFraction() {
        let row = PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 720_000, chapterDurationMs: 1_200_000, bookPositionMs: 0, bookDurationMs: 0))
        #expect(row.chapterFraction == 0.6)
        let empty = PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 0, chapterDurationMs: 0, bookPositionMs: 0, bookDurationMs: 0))
        #expect(empty.chapterFraction == 0)
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
        chapterDurationMs: Int64 = 60_000,
        speed: Float = 1.5,
        defaultSpeed: Float = 1.0,
        numberOffset: Int = 0
    ) -> PlayerUiStateLoaded {
        let total = wholeBook ? 1 : chapterCount
        let bookMs = Int64(chapterCount) * chapterDurationMs
        let rows = wholeBook ? [] : (0..<total).map { index in
            PlayerChapterRow(
                index: Int32(index), number: Int32(index + 1 + numberOffset), title: "Chapter \(index + 1)",
                startMs: Int64(index) * chapterDurationMs, durationMs: chapterDurationMs,
                isCurrent: index == currentIndex)
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
                index: Int32(wholeBook ? 0 : currentIndex), number: Int32(wholeBook ? 1 : currentIndex + 1 + numberOffset),
                total: Int32(total), title: wholeBook ? "" : (chapterTitle ?? "Chapter \(currentIndex + 1)"),
                isGenericTitle: wholeBook || isGenericTitle, isWholeBook: wholeBook,
                startMs: wholeBook ? 0 : Int64(currentIndex) * chapterDurationMs,
                durationMs: wholeBook ? bookMs : chapterDurationMs),
            chapters: rows,
            source: source,
            settings: PlayerSettings(
                speed: speed, defaultSpeed: defaultSpeed, boostDb: boostDb, defaultBoostDb: 0, skipBackSec: 10,
                skipForwardSec: 30),
            sleepTimer: sleepTimer,
            primary: primary,
            showsSkipBack: showsSkipBack,
            canSkipToNextChapter: !wholeBook && currentIndex < total - 1,
            finished: finished
        )
    }
}
