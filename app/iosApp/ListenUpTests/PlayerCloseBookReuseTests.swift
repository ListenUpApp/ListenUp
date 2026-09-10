import Testing
@testable import ListenUp

/// Closing a book (the player's "Close book" item) and signing out both call
/// `PlayerCoordinator.stop()`. Before this suite, `stop()` released the engine terminally and
/// cancelled every subscription, so the app-wide cached coordinator could never play again.
@Suite("Close book leaves the player reusable")
@MainActor
struct PlayerCloseBookReuseTests {

    /// The coordinator under test plus the fakes a test drives and observes it through.
    private struct Harness {
        let coordinator: PlayerCoordinator
        let engine: FakePlaybackEngine
        let progress: FakeProgressReporting
        let skips: FakeSkipIntervalProviding
    }

    private func makeHarness() -> Harness {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let skips = FakeSkipIntervalProviding(initialForward: 30, initialBackward: 10)
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumeBoostDb: 0, measuredGainDb: nil, normalizationGainDb: nil,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress,
            sleep: FakeSleepTiming(), engine: engine, skipIntervals: skips
        )
        return Harness(coordinator: coordinator, engine: engine, progress: progress, skips: skips)
    }

    /// The regression: play → close book → play another book must reach `.playing`, not
    /// "Couldn't start playback." on a dead engine.
    @Test func playingAgainAfterCloseBookReachesPlaying() async {
        let harness = makeHarness()
        let coordinator = harness.coordinator
        let progress = harness.progress
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        await awaitUntil { coordinator.isPlaying }

        await coordinator.stop()
        #expect(coordinator.isVisible == false)

        coordinator.play(bookId: "book2")
        // Bounded on either outcome: against a terminal engine the second load fails and the
        // coordinator lands in `.error`, so an unbounded `waitForStarted` would hang, not fail.
        await awaitUntil { coordinator.isPlaying || coordinator.isErrored }
        #expect(coordinator.isPlaying)
        #expect(coordinator.isErrored == false)
    }

    @Test func stopUnloadsRatherThanReleasingTheEngine() async {
        let harness = makeHarness()
        await harness.coordinator.stop()
        #expect(await harness.engine.teardownOrder == ["deactivate", "unload"])
        #expect(await harness.engine.didRelease == false)
    }

    /// The coordinator is an app-lifetime singleton: its subscriptions (here, the skip-interval
    /// setting) must keep delivering after a close, or the next book plays with stale settings.
    @Test func skipIntervalObservationSurvivesStop() async {
        let harness = makeHarness()
        let coordinator = harness.coordinator
        await awaitUntil { coordinator.skipForwardSec == 30 }
        await coordinator.stop()

        harness.skips.emitForward(45)
        // Bounded poll (the skip-interval suite's own pattern) so a severed subscription fails
        // the test instead of hanging an observation wait that never fires.
        await awaitUntil { coordinator.skipForwardSec == 45 }
        #expect(coordinator.skipForwardSec == 45)
    }
}
