import Testing
@testable import ListenUp

/// Coverage for the volume-boost gain stage the coordinator drives: normalization is always
/// on (measured R128 wins over the file tag, both fall back to 0 dB) and the user's boost adds
/// on top; a refined measurement is persisted when playback settles but never re-applied live.
@Suite("Volume boost")
@MainActor
struct PlayerBoostTests {
    private func makeCoordinator(
        boostDb: Float = 0,
        measuredGainDb: Float? = nil,
        normalizationGainDb: Float? = nil
    ) -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting) {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumeBoostDb: boostDb, measuredGainDb: measuredGainDb,
            normalizationGainDb: normalizationGainDb, resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(), engine: engine)
        return (coordinator, engine, progress)
    }

    /// Loading applies the combined gain, and a real measurement beats the file tag —
    /// the whole point of measuring: a trusted reading wins over a possibly-stale tag.
    @Test func loadAppliesMeasuredGainPlusBoost() async {
        let (coordinator, engine, progress) = makeCoordinator(
            boostDb: 6, measuredGainDb: -2, normalizationGainDb: 5)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        #expect(await engine.lastGainDb == 4)      // measured (-2) + boost (6), tag ignored
        #expect(coordinator.volumeBoostDb == 6)
    }

    /// No measurement yet → the server-read file tag is the normalization input.
    @Test func loadFallsBackToTagWhenUnmeasured() async {
        let (coordinator, engine, progress) = makeCoordinator(
            boostDb: 0, measuredGainDb: nil, normalizationGainDb: -3)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        #expect(await engine.lastGainDb == -3)
    }

    /// Neither input present → normalization contributes nothing and the boost stands alone.
    @Test func loadAppliesBoostAloneWhenNeitherGainKnown() async {
        let (coordinator, engine, progress) = makeCoordinator(boostDb: 2)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        #expect(await engine.lastGainDb == 2)
    }

    /// A user boost re-applies the combined gain live and persists the choice for this book.
    @Test func setBoostReappliesGainAndReportsChange() async {
        let (coordinator, engine, progress) = makeCoordinator(boostDb: 0, measuredGainDb: -2)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        coordinator.setBoost(9)

        await awaitUntil { await engine.lastGainDb == 7 }   // measured (-2) + boost (9)
        #expect(await engine.lastGainDb == 7)
        #expect(coordinator.volumeBoostDb == 9)
        #expect(progress.boostChanges.contains { $0.0 == "book1" && $0.1 == 0 && $0.2 == 9 })
    }

    /// Reset drops the book back to the global default and reports it as a reset, not a change —
    /// the two are distinct writes so a per-book override can actually be cleared.
    @Test func resetBoostAppliesDefaultAndReportsReset() async {
        let (coordinator, engine, progress) = makeCoordinator(boostDb: 9, measuredGainDb: -2)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        coordinator.resetBoost(defaultDb: 3)

        await awaitUntil { await engine.lastGainDb == 1 }   // measured (-2) + default (3)
        #expect(await engine.lastGainDb == 1)
        #expect(coordinator.volumeBoostDb == 3)
        #expect(progress.boostResets.contains { $0.0 == "book1" && $0.2 == 3 })
    }

    /// Pausing persists the refined R128 reading — the measurement is only worth having if it
    /// survives to the next session. A second pause with an unchanged reading writes nothing:
    /// sub-0.1 dB movement is meter jitter, and re-saving it would churn the sync queue.
    @Test func pauseSavesRefinedMeasurementOnceUntilItMoves() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        await engine.setMeasuredGainDb(-2.4)

        coordinator.togglePlayback()
        await engine.waitUntilPaused()
        await awaitUntil { !progress.measuredGains.isEmpty }
        #expect(progress.measuredGains.count == 1)
        #expect(progress.measuredGains.first?.0 == "book1")
        #expect(progress.measuredGains.first?.2 == -2.4)

        coordinator.togglePlayback()   // resume
        coordinator.togglePlayback()   // pause again, same reading
        await awaitUntil(timeout: .milliseconds(300)) { progress.measuredGains.count > 1 }
        #expect(progress.measuredGains.count == 1)
    }

    /// A measurement that has genuinely moved does get written on the next pause.
    @Test func pauseSavesMeasurementAgainWhenItMovesPastThreshold() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        await engine.setMeasuredGainDb(-2.4)

        coordinator.togglePlayback()
        await awaitUntil { !progress.measuredGains.isEmpty }

        await engine.setMeasuredGainDb(-4.0)
        coordinator.togglePlayback()   // resume
        coordinator.togglePlayback()   // pause with a refined reading
        await awaitUntil { progress.measuredGains.count == 2 }
        #expect(progress.measuredGains.map { $0.2 } == [-2.4, -4.0])
    }

    /// A pause with nothing measured yet writes nothing — an absent reading is not a value.
    @Test func pauseSavesNothingWhileUnmeasured() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        coordinator.togglePlayback()
        await engine.waitUntilPaused()

        await awaitUntil(timeout: .milliseconds(300)) { !progress.measuredGains.isEmpty }
        #expect(progress.measuredGains.isEmpty)
    }

    /// A book switch keeps the user's boost (like `playbackSpeed`) so the player never flashes
    /// back to 0 dB mid-switch. The per-book measurement inputs are dropped in the same breath —
    /// they belong to the outgoing book and applying them to the incoming one would be wrong.
    @Test func switchPreservesBoostAcrossMetadataReset() async {
        let (coordinator, _, progress) = makeCoordinator(boostDb: 6, measuredGainDb: -2)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        coordinator.setBoost(9)

        coordinator.play(bookId: "book2")
        // No `await`: the metadata reset is synchronous, so this reads the state the UI would
        // render during `.preparing` — boost preserved, before the incoming prepare resolves.
        #expect(coordinator.volumeBoostDb == 9)
        #expect(coordinator.bookTitle.isEmpty)   // the rest of the surface did reset
    }
}
