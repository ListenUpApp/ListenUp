import Testing
@testable import ListenUp

/// Closing a book (the shared player's `.stop`) and signing out (`PlayerCoordinator.stop()`) both
/// unload the engine. The coordinator is an app-lifetime singleton, so neither may leave it unable to
/// play the next book or with its subscriptions severed.
@Suite("Close book leaves the player reusable")
@MainActor
struct PlayerCloseBookReuseTests {
    /// The regression: play → close book → play another book must reach `.playing`, not a failure
    /// on a dead engine.
    @Test func playingAgainAfterCloseBookReachesPlaying() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1")
        await awaitObservation { harness.coordinator.isPlaying }

        await harness.run(.stop)
        #expect(harness.coordinator.engineBook == nil)

        await harness.run(.load(bookId: "book2", prepared: .fixture()))
        // Bounded on either outcome: against a terminal engine the second load fails.
        await awaitObservation { harness.coordinator.isPlaying || !harness.facts.failures.isEmpty }
        #expect(harness.coordinator.isPlaying)
        #expect(harness.facts.failures.isEmpty)
    }

    @Test func stopUnloadsRatherThanReleasingTheEngine() async {
        let harness = PlayerHarness()
        await harness.coordinator.stop()
        #expect(await harness.engine.teardownOrder == ["deactivate", "unload"])
        #expect(await harness.engine.didRelease == false)
    }

    /// Its subscriptions (here, the skip-interval setting) keep delivering after a close.
    @Test func skipIntervalObservationSurvivesStop() async {
        let skips = FakeSkipIntervalProviding(initialForward: 30, initialBackward: 10)
        let harness = PlayerHarness(skipIntervals: skips)
        await awaitObservation { harness.coordinator.skipForwardSec == 30 }
        await harness.coordinator.stop()

        skips.emitForward(45)
        await awaitObservation { harness.coordinator.skipForwardSec == 45 }
        #expect(harness.coordinator.skipForwardSec == 45)
    }
}
