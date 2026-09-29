import Testing
@testable import ListenUp

/// The Playback menu dims what cannot run and names play/pause for what it will do.
@Suite("Playback commands")
struct PlaybackCommandStateTests {
    private let loaded = PlayingState(bookId: "b1", durationMs: 60_000)

    @Test func nothingLoadedDisablesEverything() {
        #expect(PlaybackCommandState.from(phase: .idle, chapterIndex: 0, totalChapters: 0) == .unavailable)
        let preparing = PlaybackCommandState.from(
            phase: .preparing(PreparingState(bookId: "b1")), chapterIndex: 0, totalChapters: 3
        )
        #expect(preparing == .unavailable)
    }

    @Test func aPausedBookOffersPlayAndEverySeek() {
        let state = PlaybackCommandState.from(phase: .paused(loaded), chapterIndex: 1, totalChapters: 3)
        #expect(state.canTogglePlayback)
        #expect(!state.showsPause)
        #expect(state.canSeek)
        #expect(state.hasPreviousChapter)
        #expect(state.hasNextChapter)
    }

    @Test func playingAndBufferingOfferPause() {
        #expect(PlaybackCommandState.from(phase: .playing(loaded), chapterIndex: 0, totalChapters: 1).showsPause)
        #expect(PlaybackCommandState.from(phase: .buffering(loaded), chapterIndex: 0, totalChapters: 1).showsPause)
    }

    @Test func chapterJumpsStopAtTheEnds() {
        let first = PlaybackCommandState.from(phase: .playing(loaded), chapterIndex: 0, totalChapters: 3)
        #expect(!first.hasPreviousChapter)
        #expect(first.hasNextChapter)
        let last = PlaybackCommandState.from(phase: .playing(loaded), chapterIndex: 2, totalChapters: 3)
        #expect(last.hasPreviousChapter)
        #expect(!last.hasNextChapter)
    }

    @Test func anErroredBookCanBeRetriedButNotSought() {
        let state = PlaybackCommandState.from(
            phase: .error(ErrorState(message: "x", bookId: "b1")), chapterIndex: 0, totalChapters: 3
        )
        #expect(state.canTogglePlayback)
        #expect(!state.showsPause)
        #expect(!state.canSeek)
    }
}
