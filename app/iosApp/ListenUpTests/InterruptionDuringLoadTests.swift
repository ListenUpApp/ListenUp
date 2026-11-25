import Testing
@testable import ListenUp

/// An interruption or a pausing route change that lands while a book loads keeps it from starting, and a resume
/// that follows lets it start. Each test syncs on `pauseCause` (observable, set by the coordinator the
/// moment it handles the notification), or on `session.settle` where a post must change nothing, before releasing
/// the load, so the interleaving is forced, not slept.
@Suite("Interruption during a load", .serialized)
struct InterruptionDuringLoadTests {
    private let session = AudioSessionPoster()

    private func startBlockedLoad(_ harness: PlayerHarness) async {
        await harness.engine.setBlockLoad(true)
        harness.instructions.send(.load(bookId: "b1", prepared: .fixture()))
        await harness.engine.waitForLoadEntered()
    }

    private func expectLoadedPaused(_ harness: PlayerHarness) async {
        await awaitObservation { harness.coordinator.enginePhase == .paused }
        #expect(await harness.engine.playCount == 0)
        #expect(harness.progress.startedCalls.isEmpty)
        #expect(harness.progress.pausedCalls.isEmpty)
        #expect(harness.coordinator.engineBook?.bookId == "b1")
    }

    @Test("An interruption that begins while the book loads leaves it loaded and paused")
    func interruptionBeganWhileLoading() async {
        let harness = PlayerHarness(notificationCenter: session.center)
        await startBlockedLoad(harness)

        session.interruptionBegan()
        await awaitObservation { harness.coordinator.pauseCause == .interruption }
        await harness.engine.releaseLoad()

        await expectLoadedPaused(harness)
    }

    @Test("A pausing route change while the book loads leaves it loaded and paused")
    func routeChangeWhileLoading() async {
        let harness = PlayerHarness(notificationCenter: session.center)
        await startBlockedLoad(harness)

        session.routeLost()
        await awaitObservation { harness.coordinator.pauseCause == .routeLost }
        await harness.engine.releaseLoad()

        await expectLoadedPaused(harness)
    }

    @Test("An interruption that ends with shouldResume while the book loads lets it start")
    func interruptionEndedWhileLoadingResumes() async {
        let harness = PlayerHarness(notificationCenter: session.center)
        await startBlockedLoad(harness)

        session.interruptionBegan()
        await awaitObservation { harness.coordinator.pauseCause == .interruption }
        session.interruptionEnded(shouldResume: true)
        await awaitObservation { harness.coordinator.pauseCause == nil }
        await harness.engine.releaseLoad()

        await harness.progress.waitForStarted(bookId: "b1")
        #expect(await harness.engine.playCount == 1)
    }

    @Test("Headphones out while the book loads, then a call: the call's end does not start it")
    func routeLossWhileLoadingSurvivesALaterInterruption() async {
        let harness = PlayerHarness(notificationCenter: session.center)
        await startBlockedLoad(harness)

        session.routeLost()
        await awaitObservation { harness.coordinator.pauseCause == .routeLost }
        session.interruptionBegan()
        session.interruptionEnded(shouldResume: true)
        await session.settle(harness)
        #expect(harness.coordinator.pauseCause == .routeLost)
        await harness.engine.releaseLoad()

        await expectLoadedPaused(harness)
    }

    @Test("A pause while the book loads, then a call: the call's end does not start it")
    func userPauseWhileLoadingSurvivesALaterInterruption() async {
        let harness = PlayerHarness(notificationCenter: session.center)
        await startBlockedLoad(harness)

        await harness.run(.pause)
        session.interruptionBegan()
        session.interruptionEnded(shouldResume: true)
        await session.settle(harness)
        #expect(harness.coordinator.pauseCause == nil)
        #expect(harness.coordinator.playWhenLoaded == false)
        await harness.engine.releaseLoad()

        await expectLoadedPaused(harness)
    }
}
