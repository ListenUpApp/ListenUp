import Testing
@testable import ListenUp

/// Coverage for the volume-boost gain stage the coordinator drives: normalization is always
/// on (measured R128 wins over the file tag, both fall back to 0 dB) and the user's boost adds
/// on top; a refined measurement is persisted when playback settles but never re-applied live.
@Suite("Volume boost")
@MainActor
struct PlayerBoostTests {
    private func book(boostDb: Float = 0, measuredGainDb: Float? = nil, normalizationGainDb: Float? = nil)
        -> PreparedPlayback {
        .fixture(resumeBoostDb: boostDb, measuredGainDb: measuredGainDb, normalizationGainDb: normalizationGainDb)
    }

    /// Loading applies the combined gain, and a real measurement beats the file tag.
    @Test func loadAppliesMeasuredGainPlusBoost() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", book(boostDb: 6, measuredGainDb: -2, normalizationGainDb: 5))

        #expect(await harness.engine.lastGainDb == 4)      // measured (-2) + boost (6), tag ignored
        #expect(harness.coordinator.volumeBoostDb == 6)
    }

    /// No measurement yet → the server-read file tag is the normalization input.
    @Test func loadFallsBackToTagWhenUnmeasured() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", book(normalizationGainDb: -3))

        #expect(await harness.engine.lastGainDb == -3)
    }

    /// Neither input present → normalization contributes nothing and the boost stands alone.
    @Test func loadAppliesBoostAloneWhenNeitherGainKnown() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", book(boostDb: 2))

        #expect(await harness.engine.lastGainDb == 2)
    }

    /// A boost from the shared player re-applies the combined gain live and persists the choice.
    @Test func setBoostReappliesGainAndReportsChange() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", book(measuredGainDb: -2))

        await harness.run(.setBoost(9, isReset: false))

        await awaitUntil { await harness.engine.lastGainDb == 7 }   // measured (-2) + boost (9)
        #expect(await harness.engine.lastGainDb == 7)
        #expect(harness.coordinator.volumeBoostDb == 9)
        #expect(harness.progress.boostChanges.contains { $0.0 == "book1" && $0.1 == 0 && $0.2 == 9 })
    }

    /// Reset drops the book back to the global default and reports it as a reset, not a change.
    @Test func resetBoostAppliesDefaultAndReportsReset() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", book(boostDb: 9, measuredGainDb: -2))

        await harness.run(.setBoost(3, isReset: true))

        await awaitUntil { await harness.engine.lastGainDb == 1 }   // measured (-2) + default (3)
        #expect(await harness.engine.lastGainDb == 1)
        #expect(harness.coordinator.volumeBoostDb == 3)
        #expect(harness.progress.boostResets.contains { $0.0 == "book1" && $0.2 == 3 })
    }

    /// Pausing persists the refined R128 reading. A second pause with an unchanged reading writes
    /// nothing: sub-0.1 dB movement is meter jitter.
    @Test func pauseSavesRefinedMeasurementOnceUntilItMoves() async {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        await harness.engine.setMeasuredGainDb(-2.4)

        await harness.run(.pause)
        await awaitUntil { !harness.progress.measuredGains.isEmpty }
        #expect(harness.progress.measuredGains.count == 1)
        #expect(harness.progress.measuredGains.first?.0 == "book1")
        #expect(harness.progress.measuredGains.first?.2 == -2.4)

        await harness.run(.play)
        await harness.run(.pause)   // same reading
        await awaitUntil(timeout: .milliseconds(300)) { harness.progress.measuredGains.count > 1 }
        #expect(harness.progress.measuredGains.count == 1)
    }

    /// A measurement that has genuinely moved does get written on the next pause.
    @Test func pauseSavesMeasurementAgainWhenItMovesPastThreshold() async {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        await harness.engine.setMeasuredGainDb(-2.4)

        await harness.run(.pause)
        await awaitUntil { !harness.progress.measuredGains.isEmpty }

        await harness.engine.setMeasuredGainDb(-4.0)
        await harness.run(.play)
        await harness.run(.pause)
        await awaitUntil { harness.progress.measuredGains.count == 2 }
        #expect(harness.progress.measuredGains.map { $0.2 } == [-2.4, -4.0])
    }

    /// A pause with nothing measured yet writes nothing — an absent reading is not a value.
    @Test func pauseSavesNothingWhileUnmeasured() async {
        let harness = PlayerHarness()
        await harness.loadAndStart()

        await harness.run(.pause)

        await awaitUntil(timeout: .milliseconds(300)) { !harness.progress.measuredGains.isEmpty }
        #expect(harness.progress.measuredGains.isEmpty)
    }

    /// A save mid-flight when the shared player switches books must be abandoned, not landed: writing
    /// the outgoing book's reading into the incoming book's state would suppress its own first save.
    @Test func switchDuringMeasurementSaveAbandonsTheStaleReading() async {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        await harness.engine.setMeasuredGainDb(-2.4)
        await harness.engine.setBlockMeasuredGain(true)

        await harness.run(.pause)                         // the save starts…
        await harness.engine.waitForMeasuredGainRead()    // …and suspends inside the engine read

        await harness.engine.setBlockLoad(true)
        await harness.run(.load(bookId: "book2", prepared: .fixture()))
        await harness.engine.releaseMeasuredGain()        // now the stale save resumes

        await awaitUntil(timeout: .milliseconds(300)) { !harness.progress.measuredGains.isEmpty }
        #expect(harness.progress.measuredGains.isEmpty)
        #expect(harness.coordinator.gain.lastSavedMeasuredGainDb == nil)   // book2's state stayed clean
        await harness.engine.releaseLoad()
    }
}
