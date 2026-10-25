import Testing
@testable import ListenUp

/// The Playback menu dims what cannot run and names play/pause for what it will do.
@Suite("Playback commands")
struct PlaybackCommandStateTests {
    /// A loaded book's snapshot; each test changes what it is about.
    private func snapshot(
        primary: PlayerPrimary = .play,
        isPlaying: Bool = false,
        isBuffering: Bool = false,
        showsSkipBack: Bool = true,
        chapterIndex: Int = 0,
        canSkipToNextChapter: Bool = true,
        sleepTimerActive: Bool = false,
        sleepTimerIsEndOfChapter: Bool = false
    ) -> PlayerSnapshot {
        var snapshot = PlayerSnapshot()
        snapshot.isVisible = true
        snapshot.primary = primary
        snapshot.isPlaying = isPlaying
        snapshot.isBuffering = isBuffering
        snapshot.showsSkipBack = showsSkipBack
        snapshot.chapterIndex = chapterIndex
        snapshot.canSkipToNextChapter = canSkipToNextChapter
        snapshot.sleepTimerActive = sleepTimerActive
        snapshot.sleepTimerIsEndOfChapter = sleepTimerIsEndOfChapter
        return snapshot
    }

    @Test func nothingLoadedDisablesEverything() {
        #expect(PlaybackCommandState.from(snapshot: PlayerSnapshot()) == .unavailable)
    }

    @Test func aPreparingBookCanNeitherToggleNorSeek() {
        var preparing = snapshot(primary: .busy, showsSkipBack: false)
        preparing.isPreparing = true
        let state = PlaybackCommandState.from(snapshot: preparing)
        #expect(!state.canTogglePlayback)
        #expect(!state.canSeek)
        #expect(!state.hasPreviousChapter)
    }

    @Test func aPausedBookOffersPlayAndEverySeek() {
        let state = PlaybackCommandState.from(snapshot: snapshot(chapterIndex: 1))
        #expect(state.canTogglePlayback)
        #expect(!state.showsPause)
        #expect(state.canSeek)
        #expect(state.hasPreviousChapter)
        #expect(state.hasNextChapter)
    }

    /// §3.3: previous in chapter 1 restarts chapter 1, so ⌥⌘← is live there (it used to grey out).
    @Test func previousChapterIsLiveInChapterOne() {
        #expect(PlaybackCommandState.from(snapshot: snapshot(chapterIndex: 0)).hasPreviousChapter)
    }

    @Test func speedStepsStopAtTheEndsOfTheRange() {
        var slowest = snapshot()
        slowest.playbackSpeed = 0.5
        var fastest = snapshot()
        fastest.playbackSpeed = 3.0
        #expect(!PlaybackCommandState.from(snapshot: slowest).canStepSpeedDown)
        #expect(PlaybackCommandState.from(snapshot: slowest).canStepSpeedUp)
        #expect(!PlaybackCommandState.from(snapshot: fastest).canStepSpeedUp)
        #expect(PlaybackCommandState.from(snapshot: fastest).canStepSpeedDown)
    }

    @Test func showChaptersNeedsChapters() {
        var chaptered = snapshot()
        chaptered.chapterRows = [ChapterRowModel(id: "c1", title: "Prelude", startMs: 0, durationMs: 1_000)]
        #expect(PlaybackCommandState.from(snapshot: chaptered).canShowChapters)
        #expect(!PlaybackCommandState.from(snapshot: snapshot()).canShowChapters)
    }

    /// ⌘0 opens the player when it is not showing; Esc closes it only once none of its sheets is open.
    @Test func showAndCloseFollowThePlayerAndItsSheets() {
        let closed = PlaybackCommandState.from(snapshot: snapshot(), isPlayerPresented: false)
        #expect(closed.canShowNowPlaying)
        #expect(!closed.canClosePlayer)

        let open = PlaybackCommandState.from(snapshot: snapshot(), isPlayerPresented: true)
        #expect(!open.canShowNowPlaying)
        #expect(open.canClosePlayer)

        let sheet = PlaybackCommandState.from(snapshot: snapshot(), isPlayerPresented: true, playerSheetIsOpen: true)
        #expect(!sheet.canClosePlayer)
    }

    /// The PDF reader covers the player as a sheet does, so Esc must not close the player behind it: Close Player waits.
    @Test func theDocumentReaderHoldsClosePlayerLikeASheet() {
        let readerUp = FullScreenPlayerView.isCovered(
            showsBoostPicker: false, showsChapterList: false, isReadingDocument: true)
        #expect(readerUp)
        let state = PlaybackCommandState.from(snapshot: snapshot(), isPlayerPresented: true, playerSheetIsOpen: readerUp)
        #expect(!state.canClosePlayer)

        #expect(!FullScreenPlayerView.isCovered(showsBoostPicker: false, showsChapterList: false, isReadingDocument: false))
        #expect(FullScreenPlayerView.isCovered(showsBoostPicker: true, showsChapterList: false, isReadingDocument: false))
        #expect(FullScreenPlayerView.isCovered(showsBoostPicker: false, showsChapterList: true, isReadingDocument: false))
    }

    @Test func playingAndBufferingOfferPause() {
        #expect(PlaybackCommandState.from(snapshot: snapshot(primary: .pause, isPlaying: true)).showsPause)
        // Buffering draws the busy spinner, and pressing it pauses: the command stays live.
        let buffering = PlaybackCommandState.from(snapshot: snapshot(primary: .busy, isBuffering: true))
        #expect(buffering.showsPause)
        #expect(buffering.canTogglePlayback)
    }

    /// Next stops at the last chapter. Previous does not stop at the first: there it restarts chapter 1 (§3.3), which
    /// is why this test changed from expecting it dimmed.
    @Test func nextStopsAtTheLastChapterAndPreviousRestartsTheFirst() {
        let first = PlaybackCommandState.from(snapshot: snapshot(chapterIndex: 0, canSkipToNextChapter: true))
        #expect(first.hasPreviousChapter)
        #expect(first.hasNextChapter)
        let last = PlaybackCommandState.from(snapshot: snapshot(chapterIndex: 2, canSkipToNextChapter: false))
        #expect(last.hasPreviousChapter)
        #expect(!last.hasNextChapter)
    }

    /// A failed book keeps its button (Retry) but has nowhere to seek: the shared player hides Back N.
    @Test func anErroredBookCanBeRetriedButNotSought() {
        let state = PlaybackCommandState.from(snapshot: snapshot(primary: .retry, showsSkipBack: false))
        #expect(state.canTogglePlayback)
        #expect(!state.showsPause)
        #expect(!state.canSeek)
    }

    /// "Add more time" stays listed but dims unless a countdown is running on a loaded book.
    @Test func onlyARunningCountdownOnALoadedBookCanBeExtended() {
        #expect(PlaybackCommandState.from(snapshot: snapshot(sleepTimerActive: true)).canExtendSleepTimer)
        let endOfChapter = snapshot(sleepTimerActive: true, sleepTimerIsEndOfChapter: true)
        #expect(!PlaybackCommandState.from(snapshot: endOfChapter).canExtendSleepTimer)
        #expect(!PlaybackCommandState.from(snapshot: snapshot()).canExtendSleepTimer)
        var nothingLoaded = PlayerSnapshot()
        nothingLoaded.sleepTimerActive = true
        #expect(!PlaybackCommandState.from(snapshot: nothingLoaded).canExtendSleepTimer)
    }
}
