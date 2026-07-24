import Testing
@testable import ListenUp

@Suite("PlayerPhase")
struct PlayerPhaseTests {
    private let loaded = PlayingState(bookId: "book-1", durationMs: 3_600_000)

    @Test func playingStateIsPresentForLoadedPhases() {
        #expect(PlayerPhase.playing(loaded).playingState == loaded)
        #expect(PlayerPhase.paused(loaded).playingState == loaded)
        #expect(PlayerPhase.buffering(loaded).playingState == loaded)
    }

    @Test func playingStateIsNilForUnloadedPhases() {
        #expect(PlayerPhase.idle.playingState == nil)
        #expect(PlayerPhase.preparing(PreparingState(bookId: "b")).playingState == nil)
        #expect(PlayerPhase.error(ErrorState(message: "x", bookId: nil)).playingState == nil)
    }

    @Test func isPlayingOnlyTrueForPlaying() {
        #expect(PlayerPhase.playing(loaded).isPlaying)
        #expect(!PlayerPhase.paused(loaded).isPlaying)
        #expect(!PlayerPhase.buffering(loaded).isPlaying)
        #expect(!PlayerPhase.idle.isPlaying)
    }

    @Test func bookIdResolvesAcrossPhases() {
        #expect(PlayerPhase.idle.bookId == nil)
        #expect(PlayerPhase.preparing(PreparingState(bookId: "b1")).bookId == "b1")
        #expect(PlayerPhase.playing(loaded).bookId == "book-1")
        #expect(PlayerPhase.error(ErrorState(message: "x", bookId: "b2")).bookId == "b2")
    }

    @Test func phasesAreValueEqual() {
        #expect(PlayerPhase.playing(loaded) == PlayerPhase.playing(loaded))
        #expect(PlayerPhase.playing(loaded) != PlayerPhase.paused(loaded))
    }
}
