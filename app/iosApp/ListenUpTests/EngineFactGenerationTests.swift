import Testing
@testable import ListenUp

/// Every fact the coordinator reports names the load it is about, so the shared player can drop a late fact
/// about a book it has already moved on from (a failure from the old book must never fail the new one).
@Suite("Engine facts carry their load's generation")
@MainActor
struct EngineFactGenerationTests {
    @Test("Facts carry the generation of the load being executed, and move on with the next load")
    func factsCarryTheLoadGeneration() async {
        let harness = PlayerHarness()
        await harness.run(.load(bookId: "a", prepared: .fixture(), generation: 7))
        await harness.progress.waitForStarted(bookId: "a")
        // `rate: 0` holds the playhead exactly; a positive rate would interpolate past 1200.
        harness.engine.emit(.position(ms: 1200, rate: 0.0))
        await awaitObservation { harness.coordinator.bookPositionMs == 1200 }
        #expect(!harness.facts.generations.isEmpty)
        #expect(harness.facts.generations.allSatisfy { $0 == 7 })

        let reportedForA = harness.facts.generations.count
        await harness.run(.load(bookId: "b", prepared: .fixture(), generation: 8))
        await harness.progress.waitForStarted(bookId: "b")
        harness.engine.emit(.failed(.connectionLost(detail: nil)))
        await awaitObservation { harness.coordinator.engineBook == nil }

        #expect(harness.facts.generations.dropFirst(reportedForA).allSatisfy { $0 == 8 })
        #expect(harness.facts.failureGenerations == [8])
    }

    @Test("Sign-out's unload names the load it unloads")
    func unloadCarriesTheGeneration() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("a", .fixture(), generation: 3)
        await harness.coordinator.stop()
        #expect(harness.facts.unloadedCount == 1)
        #expect(harness.facts.generations.last == 3)
    }
}
