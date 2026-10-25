import Testing
import AVFoundation
@testable import ListenUp
import Shared

@Suite("ChapterMath")
struct PlayerCoordinatorTests {
    /// The index runs over the native `ChapterRowModel` projection, never the bridged Kotlin
    /// `[Chapter]`: it is recomputed on every engine position tick (~4×/s), and each bridged
    /// property read crosses the Swift Export boundary.
    private func chapter(_ id: String, start: Int64, duration: Int64) -> ChapterRowModel {
        ChapterRowModel(id: id, title: id, startMs: start, durationMs: duration)
    }

    @Test func indexIsNilForEmpty() {
        #expect(ChapterMath.index(forPositionMs: 0, in: []) == nil)
    }

    @Test func indexFindsContainingChapter() {
        let chapters = [
            chapter("c0", start: 0, duration: 1000),
            chapter("c1", start: 1000, duration: 2000),
            chapter("c2", start: 3000, duration: 500)
        ]
        #expect(ChapterMath.index(forPositionMs: 0, in: chapters) == 0)
        #expect(ChapterMath.index(forPositionMs: 999, in: chapters) == 0)
        #expect(ChapterMath.index(forPositionMs: 1000, in: chapters) == 1)
        #expect(ChapterMath.index(forPositionMs: 2999, in: chapters) == 1)
        #expect(ChapterMath.index(forPositionMs: 3000, in: chapters) == 2)
    }

    @Test func indexClampsPastEndToLastChapter() {
        let chapters = [chapter("c0", start: 0, duration: 1000)]
        #expect(ChapterMath.index(forPositionMs: 99_999, in: chapters) == 0)
    }
}

@Suite("PlaybackEngine seam")
struct PlaybackEngineSeamTests {
    @Test func fakeEngineRecordsPause() async {
        let engine = FakePlaybackEngine()
        await engine.pause()
        #expect(await engine.didPause)
    }
}

@Suite("PlayerCoordinator wiring")
@MainActor
struct PlayerCoordinatorWiringTests {
    /// A seek then a play on the book the engine holds is a resume in place — the shared player's
    /// "the book the engine already holds" path — never a second load.
    @Test func seekThenPlayOnTheLoadedBookDoesNotReload() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(resumePositionMs: 2000))
        await harness.run(.pause)
        let loadsBefore = await harness.engine.commandLog.filter { $0 == "load" }.count

        await harness.run(.seek(toMs: 30_000))
        await harness.run(.play)
        await harness.engine.waitUntilSeeked()

        #expect(await harness.engine.lastSeekMs == 30_000)
        #expect(await harness.engine.commandLog.filter { $0 == "load" }.count == loadsBefore)
        #expect(harness.progress.seeks.last?.2 == 30_000)
    }

    /// The prepared book's resume position is where the engine starts (the shared preparer already
    /// resolved any requested start position into it).
    @Test func loadStartsTheEngineAtThePreparedPosition() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart("book2", .fixture(resumePositionMs: 12_000))
        #expect(await harness.engine.lastLoadStartMs == 12_000)
    }

    @Test func loadStartsEngineAtResumePositionAndSpeed() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(resumePositionMs: 2000, resumeSpeed: 1.5))
        #expect(await harness.engine.didPlay)
        #expect(await harness.engine.lastRate == 1.5)
        #expect(harness.progress.startedCalls.first?.0 == "book1")
        #expect(harness.progress.startedCalls.first?.1 == 2000)
    }

    /// The load reports buffering at once, then playing when the engine says audio advances.
    @Test func loadReportsBufferingThenPlaying() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        await awaitObservation { harness.coordinator.isPlaying }
        #expect(harness.facts.phases.prefix(2) == [.buffering, .playing])
    }

    /// A rate reset is persisted as a reset, so the book stops being an exception to the default.
    @Test func rateResetIsReportedAsAReset() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        await harness.run(.setRate(1.25, isReset: false))
        await harness.run(.setRate(1.0, isReset: true))
        #expect(harness.progress.speedChanges.map(\.2) == [1.25])
        #expect(harness.progress.speedResets.map(\.2) == [1.0])
        await awaitUntil { await harness.engine.lastRate == 1.0 }
        #expect(await harness.engine.lastRate == 1.0)
    }
}

@Suite("Save current position")
@MainActor
struct SaveCurrentPositionTests {
    @Test func saveCurrentPositionPersistsImmediately() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(resumePositionMs: 4321))

        await harness.coordinator.saveCurrentPosition()

        #expect(harness.progress.savedNow.contains { $0.0 == "book1" })
    }

    @Test func saveCurrentPositionNoopsWhenIdle() async {
        let harness = PlayerHarness()
        await harness.coordinator.saveCurrentPosition()
        #expect(harness.progress.savedNow.isEmpty)
    }
}

@Suite("Interruption policy")
struct InterruptionPolicyTests {
    @Test func beganPauses() {
        #expect(InterruptionPolicy.action(type: .began, shouldResume: false) == .pause)
    }
    @Test func endedWithResumeResumes() {
        #expect(InterruptionPolicy.action(type: .ended, shouldResume: true) == .resume)
    }
    @Test func endedWithoutResumeDoesNothing() {
        #expect(InterruptionPolicy.action(type: .ended, shouldResume: false) == InterruptionPolicy.Action.none)
    }
}

@Suite("Route change handling")
@MainActor
struct RouteChangeTests {
    /// A private center per test instance — the coordinator observes THIS, not the process-global
    /// `.default`, so this suite's route-change post can never leak into a concurrent suite's coordinator.
    private let center = NotificationCenter()

    @Test func routeChangeOldDeviceUnavailablePausesAndTellsTheSharedPlayer() async {
        let harness = PlayerHarness(notificationCenter: center)
        await harness.loadAndStart()

        center.post(
            name: AVAudioSession.routeChangeNotification, object: nil,
            userInfo: [AVAudioSessionRouteChangeReasonKey:
                NSNumber(value: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue)])

        await harness.engine.waitUntilPaused()
        #expect(await harness.engine.didPause)
        await awaitObservation { harness.coordinator.enginePhase == .paused }
        #expect(harness.facts.phases.last == .paused)
        // Every route change re-reports the AirPlay route (none on the simulator).
        #expect(!harness.facts.routes.isEmpty)
    }
}

// Each test instance owns a private `NotificationCenter` and injects it into its coordinator, so an
// interruption post here reaches ONLY this test's coordinator. `.serialized` is kept as belt-and-suspenders.
@Suite("Audio-session interruption handling", .serialized)
@MainActor
struct AudioSessionInterruptionTests {
    private let center = NotificationCenter()

    private func postInterruptionBegan() {
        center.post(
            name: AVAudioSession.interruptionNotification, object: nil,
            userInfo: [AVAudioSessionInterruptionTypeKey:
                NSNumber(value: AVAudioSession.InterruptionType.began.rawValue)])
    }

    private func postInterruptionEnded(shouldResume: Bool) {
        var userInfo: [AnyHashable: Any] = [
            AVAudioSessionInterruptionTypeKey:
                NSNumber(value: AVAudioSession.InterruptionType.ended.rawValue)
        ]
        if shouldResume {
            userInfo[AVAudioSessionInterruptionOptionKey] =
                NSNumber(value: AVAudioSession.InterruptionOptions.shouldResume.rawValue)
        }
        center.post(
            name: AVAudioSession.interruptionNotification, object: nil, userInfo: userInfo)
    }

    @Test func interruptionBeganPausesPlayback() async {
        let harness = PlayerHarness(notificationCenter: center)
        await harness.loadAndStart()

        postInterruptionBegan()
        await harness.engine.waitUntilPaused()
        await awaitObservation { !harness.coordinator.isPlaybackActive }

        #expect(await harness.engine.didPause)
        #expect(harness.coordinator.isPlaying == false)
        #expect(harness.progress.pausedCalls.count >= 1)
    }

    @Test func interruptionEndedWithShouldResumeResumesAfterInterruptionPause() async {
        let harness = PlayerHarness(notificationCenter: center)
        await harness.loadAndStart()

        postInterruptionBegan()
        await harness.engine.waitUntilPaused()
        postInterruptionEnded(shouldResume: true)
        await harness.engine.waitForPlayCount(2)
        await awaitObservation { harness.coordinator.isPlaying }

        #expect(harness.coordinator.isPlaying)
        let log = await harness.engine.commandLog
        #expect(Array(log.suffix(2)) == ["activate", "play"])
        #expect(await harness.engine.didActivateSession)
    }

    @Test func interruptionEndedWithoutShouldResumeStaysPaused() async {
        let harness = PlayerHarness(notificationCenter: center)
        await harness.loadAndStart()

        postInterruptionBegan()
        await harness.engine.waitUntilPaused()
        postInterruptionEnded(shouldResume: false)

        await awaitUntil(timeout: .milliseconds(300)) { harness.coordinator.isPlaying }
        #expect(harness.coordinator.isPlaying == false)
        #expect(await harness.engine.playCount == 1)
    }

    @Test func interruptionEndedDoesNotResumeUserInitiatedPause() async {
        let harness = PlayerHarness(notificationCenter: center)
        await harness.loadAndStart()

        await harness.run(.pause)   // the user pauses deliberately
        await harness.engine.waitUntilPaused()
        postInterruptionEnded(shouldResume: true)

        await awaitUntil(timeout: .milliseconds(300)) { harness.coordinator.isPlaying }
        #expect(harness.coordinator.isPlaying == false)
        #expect(await harness.engine.playCount == 1)
    }

    @Test func interruptionBeganWhileBufferingPauses() async {
        let harness = PlayerHarness(notificationCenter: center)
        await harness.loadAndStart()
        await awaitObservation { harness.coordinator.isPlaying }

        harness.engine.emit(.statusChanged(.buffering))
        await awaitObservation { harness.coordinator.isBuffering }

        postInterruptionBegan()
        await harness.engine.waitUntilPaused()
        await awaitObservation { !harness.coordinator.isBuffering }

        #expect(harness.coordinator.isPlaying == false)
        #expect(harness.coordinator.isBuffering == false)
    }

    @Test func stopSeversInterruptionObservation() async {
        let harness = PlayerHarness(notificationCenter: center)
        await harness.loadAndStart()

        await harness.coordinator.stop()
        let pausesBefore = await harness.engine.commandLog.filter { $0 == "pause" }.count

        postInterruptionBegan()
        await awaitUntil(timeout: .milliseconds(300)) {
            await harness.engine.commandLog.filter { $0 == "pause" }.count > pausesBefore
        }
        #expect(await harness.engine.commandLog.filter { $0 == "pause" }.count == pausesBefore)
    }
}

@Suite("Route change policy")
struct RouteChangePolicyTests {
    @Test func oldDeviceUnavailablePauses() {
        #expect(RouteChangePolicy.shouldPause(reason: .oldDeviceUnavailable))
    }
    @Test func newDeviceAvailableDoesNotPause() {
        #expect(RouteChangePolicy.shouldPause(reason: .newDeviceAvailable) == false)
    }
    @Test func categoryChangeDoesNotPause() {
        #expect(RouteChangePolicy.shouldPause(reason: .categoryChange) == false)
    }
}

@Suite("Skip interval wiring")
@MainActor
struct SkipIntervalTests {
    /// With no override, the lock screen's forward and backward skips use their *distinct* default
    /// intervals (30 / 10). (The in-app skips are the shared player's, tested in Kotlin.)
    @Test func defaultRemoteForwardAndBackwardUseDistinctIntervals() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(durationMs: 600_000))

        // `rate: 0` (paused) holds the position exactly; a positive rate starts the display link.
        harness.engine.emit(.position(ms: 60000, rate: 0.0))
        await awaitObservation { harness.coordinator.bookPositionMs == 60000 }

        harness.coordinator.remoteSkipForward()
        await harness.progress.waitForSeek(bookId: "book1", afterMs: 90000)
        #expect(harness.progress.seeks.contains { $0.0 == "book1" && $0.2 == 90000 })

        harness.coordinator.remoteSkipBackward()
        await harness.progress.waitForSeek(bookId: "book1", afterMs: 50000)
        #expect(harness.progress.seeks.contains { $0.0 == "book1" && $0.2 == 50000 })
    }

    /// §3.3: a lock-screen, AirPods or CarPlay skip moves what the app's does — the interval × speed.
    @Test func remoteSkipsMoveTheIntervalTimesTheSpeed() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(durationMs: 600_000, resumeSpeed: 2.0))
        harness.engine.emit(.position(ms: 300_000, rate: 0.0))
        await awaitObservation { harness.coordinator.bookPositionMs == 300_000 }

        harness.coordinator.remoteSkipBackward()
        // 10 s of listening at 2× is 20 s of book.
        await harness.progress.waitForSeek(bookId: "book1", afterMs: 280_000)
        #expect(harness.progress.seeks.contains { $0.0 == "book1" && $0.2 == 280_000 })
    }

    /// The provider's seeded values flow into the coordinator.
    @Test func seededIntervalsApply() async {
        let skip = FakeSkipIntervalProviding(initialForward: 45, initialBackward: 15)
        let harness = PlayerHarness(skipIntervals: skip)
        await awaitObservation { harness.coordinator.skipForwardSec == 45 && harness.coordinator.skipBackwardSec == 15 }
        #expect(harness.coordinator.skipForwardSec == 45)
        #expect(harness.coordinator.skipBackwardSec == 15)
    }

    /// A live change emitted by the provider (the user picks a new interval mid-session) reaches the
    /// coordinator without a rebuild.
    @Test func liveChangePropagates() async {
        let skip = FakeSkipIntervalProviding(initialForward: 30, initialBackward: 10)
        let harness = PlayerHarness(skipIntervals: skip)
        await awaitObservation { harness.coordinator.skipForwardSec == 30 && harness.coordinator.skipBackwardSec == 10 }

        skip.emitForward(45)
        skip.emitBackward(20)
        await awaitObservation { harness.coordinator.skipForwardSec == 45 && harness.coordinator.skipBackwardSec == 20 }
        #expect(harness.coordinator.skipForwardSec == 45)
        #expect(harness.coordinator.skipBackwardSec == 20)
    }
}

@Suite("Seek persistence")
@MainActor
struct SeekPersistenceTests {
    @Test func seekReportsNewPositionToProgressAndTheSharedPlayer() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()

        // A seek splits the listening span; the reporter's onSeek both persists the new position
        // and records the split (before → after).
        await harness.run(.seek(toMs: 30000))
        await harness.progress.waitForSeek(bookId: "book1", afterMs: 30000)

        #expect(harness.progress.seeks.contains { $0.0 == "book1" && $0.2 == 30000 })
        #expect(harness.facts.positions.contains(30000))
    }
}

@Suite("Seam value types")
struct SeamValueTypeTests {
    @Test func preparedPlaybackHoldsTimeline() {
        #expect(ListenUp.PreparedPlayback.fixture().timeline.files.count == 1)
    }

    /// An item failure is typed once, at the engine edge, from its error domain and code — never its text.
    @Test func itemFailuresAreClassifiedByDomainAndCode() {
        let dropped = NSError(domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost)
        let format = NSError(domain: AVFoundationErrorDomain, code: AVError.Code.fileFormatNotRecognized.rawValue)
        let posix = NSError(domain: NSPOSIXErrorDomain, code: 5)
        if case .connectionLost = AudioEngine.failure(for: dropped) {} else {
            Issue.record("a network error should be a lost connection")
        }
        if case .unsupportedFormat = AudioEngine.failure(for: format) {} else {
            Issue.record("an unrecognised format should be an unsupported format")
        }
        if case .couldNotStart = AudioEngine.failure(for: posix) {} else {
            Issue.record("anything else could not start")
        }
        #expect(AudioEngine.failure(for: nil) == .couldNotStart(detail: nil))
    }
}

@Suite("Stop deactivates session")
@MainActor
struct StopDeactivatesSessionTests {
    @Test func stopDeactivatesAudioSession() async throws {
        let harness = PlayerHarness()
        await harness.coordinator.stop()
        #expect(await harness.engine.didDeactivateSession)
    }

    /// The teardown invariant: the audio session is deactivated *before* the engine is unloaded, and
    /// both complete before `stop()` returns.
    @Test func stopDeactivatesSessionBeforeUnloadingEngine() async throws {
        let harness = PlayerHarness()
        await harness.coordinator.stop()
        #expect(await harness.engine.teardownOrder == ["deactivate", "unload"])
        #expect(await harness.engine.didUnload)
    }

    /// The place is saved last, during teardown — exactly when the task running it is likeliest to be
    /// cancelled. The cancellation must not reach the save, or up to 5 s of listening is lost
    /// (Android runs the same save under `NonCancellable`).
    @Test func stopFinishesTheSaveWhenItsTaskIsCancelled() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(resumePositionMs: 4321))
        harness.progress.holdsSaves = true

        let stopping = Task { await harness.coordinator.stop() }
        await harness.progress.waitForSaveEntered()
        stopping.cancel()
        harness.progress.releaseHeldSave()
        await stopping.value

        #expect(harness.progress.savedNow.map(\.0) == ["book1"])
        #expect(harness.progress.cancelledSaves.isEmpty, "the teardown save saw its caller's cancellation")
    }

    /// Sign-out's stop starts in Swift, so it tells the shared player the engine is empty.
    @Test func swiftInitiatedStopReportsUnloaded() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        await harness.coordinator.stop()
        #expect(harness.facts.unloadedCount == 1)
        #expect(harness.coordinator.engineBook == nil)
    }
}

@Suite("Playback lifecycle")
@MainActor
struct PlaybackLifecycleTests {
    /// A full load → pause → stop journey leaves the engine and the reported phase consistent.
    @Test func loadPauseStopLeavesConsistentState() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()
        await awaitObservation { harness.coordinator.isPlaying }
        #expect(harness.coordinator.engineBook?.bookId == "book1")

        await harness.run(.pause)
        await harness.engine.waitUntilPaused()
        #expect(harness.coordinator.isPlaying == false)
        #expect(harness.facts.phases.last == .paused)

        await harness.run(.stop)
        #expect(await harness.engine.didDeactivateSession)
        #expect(await harness.engine.didUnload)
        #expect(harness.facts.phases.last == .idle)
    }

    /// `stop()` keeps the engine subscription bound (the coordinator is an app-lifetime singleton),
    /// so a late engine event from the unloaded book must be dropped rather than reported.
    @Test func stopIgnoresLateEngineEvents() async throws {
        let harness = PlayerHarness()
        await harness.loadAndStart()

        await harness.run(.stop)
        harness.engine.emit(.failed(.couldNotStart(detail: "late event after stop")))

        await awaitUntil(timeout: .milliseconds(300)) { !harness.facts.failures.isEmpty }
        #expect(harness.facts.failures.isEmpty, "stop() left engine observation live — a late event was reported")
    }
}

/// Next and previous track from the lock screen, AirPods and CarPlay move by chapter, by the shared rule (§3.3).
@Suite("Remote track commands")
@MainActor
struct RemoteTrackCommandTests {
    private static func chapter(_ id: String, start: Int64, duration: Int64) -> Chapter {
        Chapter(id: id, title: id, duration: duration, startTime: start, partTitle: nil, bookTitle: nil)
    }

    /// Chapters at 0, 100 and 250 s of a 300 s book.
    private static let chapters = [
        chapter("one", start: 0, duration: 100_000),
        chapter("two", start: 100_000, duration: 150_000),
        chapter("three", start: 250_000, duration: 50_000)
    ]

    private func loaded(at positionMs: Int64) async -> PlayerHarness {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(durationMs: 300_000, chapters: Self.chapters))
        harness.engine.emit(.position(ms: positionMs, rate: 0.0))
        await awaitObservation { harness.coordinator.bookPositionMs == positionMs }
        return harness
    }

    @Test func previousWithinThreeSecondsGoesToThePreviousChapter() async {
        let harness = await loaded(at: 101_000)
        harness.coordinator.remotePreviousTrack()
        await harness.progress.waitForSeek(bookId: "book1", afterMs: 0)
        #expect(harness.progress.seeks.contains { $0.0 == "book1" && $0.2 == 0 })
    }

    @Test func previousLaterInAChapterRestartsIt() async {
        let harness = await loaded(at: 120_000)
        harness.coordinator.remotePreviousTrack()
        await harness.progress.waitForSeek(bookId: "book1", afterMs: 100_000)
        #expect(harness.progress.seeks.contains { $0.0 == "book1" && $0.2 == 100_000 })
    }

    @Test func previousInChapterOneRestartsIt() async {
        let harness = await loaded(at: 500)
        harness.coordinator.remotePreviousTrack()
        await harness.progress.waitForSeek(bookId: "book1", afterMs: 0)
        #expect(harness.progress.seeks.contains { $0.0 == "book1" && $0.2 == 0 })
    }

    @Test func nextGoesToTheNextChapter() async {
        let harness = await loaded(at: 50_000)
        #expect(harness.coordinator.remoteNextTrack())
        await harness.progress.waitForSeek(bookId: "book1", afterMs: 100_000)
        #expect(harness.progress.seeks.contains { $0.0 == "book1" && $0.2 == 100_000 })
    }

    @Test func nextAtTheLastChapterDoesNothing() async {
        let harness = await loaded(at: 260_000)
        let seeksBefore = harness.progress.seeks.count
        #expect(!harness.coordinator.remoteNextTrack())
        #expect(harness.progress.seeks.count == seeksBefore)
    }
}
