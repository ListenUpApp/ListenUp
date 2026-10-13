import Testing
@testable import ListenUp

/// Critical-path coverage for the audiobook promise "never lose the user's place"
/// (iOS charter rule 13) and the engine's failure states.
///
/// `PlayerCoordinatorTests` covers save-when-loaded, save-no-op-when-idle and seek-reports-position,
/// and `ScenePhasePolicyTests` covers the background-save decision. This file fills the remaining
/// gaps: pause carries the reached position, the finish truth, and the typed failure reports.

@Suite("Pause persistence")
@MainActor
struct PausePersistenceTests {
    /// Pausing reports the user's current place. Pause is one of the three save triggers and must
    /// carry the position that was reached.
    @Test func pauseReportsCurrentPosition() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()

        // `rate: 0` (paused sample) holds the position exactly; a positive rate would start
        // the display link and interpolate past 12345.
        harness.engine.emit(.position(ms: 12345, rate: 0.0))
        await awaitObservation { harness.coordinator.bookPositionMs == 12345 }

        await harness.run(.pause)
        await harness.engine.waitUntilPaused()

        await awaitUntil { harness.progress.pausedCalls.contains { $0.0 == "book1" && $0.1 == 12345 } }
        #expect(
            harness.progress.pausedCalls.contains { $0.0 == "book1" && $0.1 == 12345 },
            "expected a pause report at 12345 for book1; recorded: \(harness.progress.pausedCalls)"
        )
    }
}

@Suite("Finish truth")
@MainActor
struct FinishTruthTests {
    /// End-of-media reports where the listener actually was, not the book's duration, and tells the
    /// shared player the engine ended.
    @Test func bookEndedReportsTheRealPosition() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        harness.engine.emit(.position(ms: 24000, rate: 0.0))
        await awaitObservation { harness.coordinator.bookPositionMs == 24000 }

        harness.engine.emit(.ended)

        await awaitUntil { !harness.progress.finished.isEmpty }
        #expect(harness.progress.finished.first?.positionMs == 24000)
        #expect(harness.progress.finished.first?.durationMs == 60000)
        await awaitObservation { harness.coordinator.enginePhase == .ended }
        #expect(harness.facts.phases.last == .ended)
    }

    /// A pause carries the book's duration, so the shared tracker can tell a pause in the end
    /// credits (the book is finished) from any other pause.
    @Test func pauseCarriesTheBookDuration() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        harness.engine.emit(.position(ms: 59800, rate: 0.0))
        await awaitObservation { harness.coordinator.bookPositionMs == 59800 }

        await harness.run(.pause)

        await awaitUntil { !harness.progress.pausedDurations.isEmpty }
        #expect(harness.progress.pausedDurations.last == 60000)
    }
}

@Suite("Buffering promotion")
@MainActor
struct BufferingPromotionTests {
    /// Audio actually advancing promotes a `.buffering` phase to `.playing`. The engine's `.ready` is a
    /// KVO *transition*; if it lands inside the loading gate it is dropped and nothing re-emits it.
    /// Position samples carry the player's real rate, and a positive rate IS "audio is advancing".
    @Test func advancingAudioPromotesBufferingToPlaying() async throws {
        let harness = PlayerHarness()
        await harness.engine.setAutoReadyOnPlay(false)
        await harness.loadAndStart()
        #expect(!harness.coordinator.isPlaying, "fresh load with no engine events should still be buffering")

        harness.engine.emit(.position(ms: 250, rate: 1.0))

        await awaitObservation { harness.coordinator.isPlaying }
        #expect(harness.coordinator.isPlaying, "advancing audio (rate > 0) must promote buffering to playing")
    }

    /// A paused-rate position sample must NOT promote — rate 0 samples arrive while genuinely buffering.
    @Test func stalledAudioDoesNotPromoteBuffering() async throws {
        let harness = PlayerHarness()
        await harness.engine.setAutoReadyOnPlay(false)
        await harness.loadAndStart()

        harness.engine.emit(.position(ms: 250, rate: 0.0))
        await awaitObservation { harness.coordinator.bookPositionMs == 250 }

        #expect(!harness.coordinator.isPlaying, "a rate-0 sample is not evidence of playback")
    }
}

@Suite("Engine failures")
@MainActor
struct EngineFailureTests {
    /// A prepared book whose timeline yields no playable segments is reported as could-not-start.
    @Test func emptyTimelineReportsCouldNotStart() async {
        let harness = PlayerHarness()
        await harness.run(.load(bookId: "book1", prepared: .fixture(localPath: nil, streamingUrl: "")))

        #expect(harness.facts.failures == [.couldNotStart(detail: "no audio")])
        #expect(harness.coordinator.engineBook == nil)
    }

    /// An engine load failure is reported typed and holds nothing, so nothing saves against it.
    @Test func loadFailureReportsCouldNotStart() async {
        let harness = PlayerHarness()
        await harness.engine.setLoadShouldFail(true)
        await harness.run(.load(bookId: "book1", prepared: .fixture()))

        await awaitObservation { harness.coordinator.engineBook == nil }
        #expect(harness.facts.failures == [.couldNotStart(detail: "engine load failed")])
        await harness.coordinator.saveCurrentPosition()
        #expect(harness.progress.savedNow.isEmpty)
    }

    /// An item failure mid-play reaches the shared player with its typed cause.
    @Test func itemFailureIsReportedTyped() async {
        let harness = PlayerHarness()
        await harness.loadAndStart()

        harness.engine.emit(.failed(.connectionLost(detail: "NSURLErrorDomain -1005")))

        await awaitObservation { harness.coordinator.engineBook == nil }
        #expect(harness.facts.failures == [.connectionLost(detail: "NSURLErrorDomain -1005")])
    }

    /// A play after a failure must not replay the preparation that just failed (its URLs, local paths and token
    /// may be what broke): the coordinator holds nothing, and the shared player prepares the book afresh.
    @Test func playAfterAFailureDoesNotReplayTheFailedPreparation() async {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        harness.engine.emit(.failed(.connectionLost(detail: nil)))
        await awaitObservation { harness.coordinator.engineBook == nil }

        await harness.run(.seek(toMs: 20000))
        await harness.run(.play)

        let log = await harness.engine.commandLog
        #expect(log.filter { $0 == "load" }.count == 1)
        #expect(!log.contains("seek"))
        #expect(harness.coordinator.engineBook == nil)
    }

    /// Never stranded from the lock screen, CarPlay or AirPods: a play there after a failure asks the shared
    /// player to play, which prepares the book afresh, tagged with the failed load's generation.
    @Test func remotePlayAfterAFailureAsksTheSharedPlayer() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(), generation: 4)
        harness.engine.emit(.failed(.connectionLost(detail: nil)))
        await awaitObservation { harness.coordinator.engineBook == nil }

        harness.coordinator.remotePlay()
        harness.coordinator.remoteTogglePlayPause()

        #expect(harness.facts.playRequests == [4, 4])
        #expect(await harness.engine.commandLog.filter { $0 == "load" }.count == 1)
    }

    /// While the coordinator holds the book, a remote play resumes the engine; it does not bother the shared player.
    @Test func remotePlayWhileHeldResumesTheEngine() async {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        await harness.run(.pause)
        harness.coordinator.remotePlay()
        await harness.engine.waitForPlayCount(2)
        #expect(harness.facts.playRequests.isEmpty)
    }
}
