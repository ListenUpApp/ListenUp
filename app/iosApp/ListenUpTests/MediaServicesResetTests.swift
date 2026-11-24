import Testing
@testable import ListenUp

// A media-services reset kills every audio object the app holds. The player must stop using the dead one: save the
// listener's place, rebuild the engine's player, and leave the book in the could-not-start state, from which Play or
// Retry prepares it afresh. Each test posts on its own center, so a reset reaches only its own coordinator; the
// `#require`s come before any wait on the save, so a coordinator that ignores the reset fails instead of hanging.
@Suite("Media-services reset", .serialized)
@MainActor
struct MediaServicesResetTests {
    private let session = AudioSessionPoster()
    private static let resetFailure = EngineFailure.couldNotStart(detail: "media services reset")

    @Test("A reset while playing rebuilds the player, saves the place and drops the book to Retry")
    func resetWhilePlayingRebuildsTheEngineAndDropsTheBook() async throws {
        let harness = PlayerHarness(notificationCenter: session.center)
        await harness.loadAndStart("book1", .fixture(resumePositionMs: 12000))
        await awaitObservation { harness.coordinator.isPlaying }

        session.mediaServicesReset()
        await session.settle(harness)

        try #require(await harness.engine.rebuildCount == 1)
        try #require(harness.facts.failures == [Self.resetFailure])
        #expect(harness.coordinator.isPlaying == false)
        #expect(harness.coordinator.currentBookId == nil)
        #expect(harness.progress.pausedCalls.contains { $0.0 == "book1" && $0.1 == 12000 })
        await harness.progress.waitForSaveNow(bookId: "book1")
        #expect(harness.progress.savedNow.contains { $0.0 == "book1" && $0.1 == 12000 })
    }

    @Test("A reset while paused rebuilds and drops the book without reporting a second pause")
    func resetWhilePausedRebuildsAndDropsTheBookWithoutAPausedReport() async throws {
        let harness = PlayerHarness(notificationCenter: session.center)
        await harness.loadAndStart("book1", .fixture(resumePositionMs: 12000))
        await harness.run(.pause)
        await harness.engine.waitUntilPaused()
        let pausesBefore = harness.progress.pausedCalls.count

        session.mediaServicesReset()
        await session.settle(harness)

        try #require(await harness.engine.rebuildCount == 1)
        try #require(harness.facts.failures == [Self.resetFailure])
        #expect(harness.progress.pausedCalls.count == pausesBefore)
        await harness.progress.waitForSaveNow(bookId: "book1")
    }

    @Test("A reset with nothing loaded only rebuilds the player")
    func resetWithNothingLoadedOnlyRebuilds() async {
        let harness = PlayerHarness(notificationCenter: session.center)

        session.mediaServicesReset()
        await session.settle(harness)

        #expect(await harness.engine.rebuildCount == 1)
        #expect(harness.facts.failures.isEmpty)
        #expect(harness.progress.savedNow.isEmpty)
    }

    @Test("After a reset, the shared player's fresh load plays on the rebuilt player")
    func aLoadAfterAResetStartsNormally() async throws {
        let harness = PlayerHarness(notificationCenter: session.center)
        await harness.loadAndStart("book1", .fixture(resumePositionMs: 12000))
        await awaitObservation { harness.coordinator.isPlaying }
        session.mediaServicesReset()
        await session.settle(harness)
        try #require(harness.facts.failures == [Self.resetFailure])

        // Retry: the shared player prepares the book afresh from the saved place and loads it again.
        await harness.run(.load(bookId: "book1", prepared: .fixture(resumePositionMs: 12000), generation: 1))
        await harness.engine.waitForPlayCount(2)
        await awaitObservation { harness.coordinator.isPlaying }

        #expect(harness.coordinator.isPlaying)
        #expect(harness.facts.failures.count == 1)
        let log = await harness.engine.commandLog
        let rebuild = try #require(log.firstIndex(of: "rebuild"))
        let reload = try #require(log.lastIndex(of: "load"))
        #expect(rebuild < reload)
        #expect(await harness.engine.lastLoadStartMs == 12000)
    }
}
