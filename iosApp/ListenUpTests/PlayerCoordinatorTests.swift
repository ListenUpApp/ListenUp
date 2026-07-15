import Testing
import AVFoundation
@testable import ListenUp
import Shared

/// Polls `condition` until true or the timeout elapses.
///
/// **Prefer the fakes' `AsyncGate` waits** (`progress.waitForStarted(bookId:)`,
/// `progress.waitForPositionUpdate(…)`, `engine.waitUntilPaused()`, etc.): they await the exact
/// state transition by causality and have no wall-clock dependence, which is what de-flaked the
/// engine/progress/sleep assertions under saturated CI. Anchor "playback has started" on the
/// coordinator's own `waitForStarted` callback (emitted *after* `phase = .playing`), never on the
/// `engine.play()` command — the latter races the coordinator's post-`play` phase write.
/// This poll remains only for the few cases that observe the coordinator's own `@Observable`
/// state set from internal `Task`s the fakes can't signal (e.g. `firstPdfDocId`,
/// `documentToOpen`) — and for the bounded *negative* check in `stopSeversEngineObservation`.
///
/// The ceiling is deliberately generous: a passing condition returns in milliseconds, so the
/// timeout is never paid on a green run — it is only ever reached when the awaited work
/// genuinely never happens. 30 s gives a saturated scheduler ample room without slowing
/// healthy runs.
@MainActor
func awaitUntil(
    timeout: Duration = .seconds(30),
    pollInterval: Duration = .milliseconds(20),
    _ condition: () async -> Bool
) async {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if await condition() { return }
        try? await Task.sleep(for: pollInterval)
    }
}

/// Causally await a coordinator `@Observable` condition — suspends until the tracked state
/// actually mutates, resuming the instant it does, with no hop ceiling and no wall-clock poll.
///
/// This replaced a fixed cooperative-hop poll that lost a real CI race. The buffering→playing
/// promotion (and, via the `prepare` path, the load-failure `.error` transition) is driven by
/// work that is NOT purely main-actor: `FlowBridge`'s `for await … in engine.events` drives
/// `AsyncStream.Iterator.next()` — a `nonisolated async` call that parks on the generic executor
/// (SE-0338) — and the prepare/cover fakes are likewise `nonisolated async`. On a CPU-starved CI
/// runner (parallel simulator clones) those off-main resumptions land on the wall clock *after* a
/// fixed hop budget has drained, so the poll exited with the condition still false and the
/// assertion failed (no hang, ~normal duration). Observation waits for the real mutation however
/// long it takes — it can't lose that race — while still surfacing a genuinely-absent mutation via
/// the test's execution-time allowance rather than a silent early pass.
///
/// (Prefer the fakes' `AsyncGate` waits where a fake can signal causally; use this for a
/// coordinator `@Observable` transition no fake owns, e.g. the engine-event-driven phase promotion.)
@MainActor
func awaitObservation(_ condition: @escaping @MainActor () -> Bool) async {
    while !condition() {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            withObservationTracking { _ = condition() } onChange: { continuation.resume() }
        }
    }
}

@Suite("ChapterMath")
struct PlayerCoordinatorTests {
    private func chapter(_ id: String, start: Int64, duration: Int64) -> Chapter {
        Chapter(id: id, title: id, duration: duration, startTime: start)
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
    private func makeCoordinator() -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting) {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let sleep = FakeSleepTiming()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil,
            resumeSpeed: 1.5, resumePositionMs: 2000,
            chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)
            ])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: sleep,
            engine: engine
        )
        return (coordinator, engine, progress)
    }

    @Test func playLoadsAndStartsEngineAtResumePosition() async throws {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        #expect(await engine.didPlay)
        #expect(await engine.lastRate == 1.5)
        #expect(progress.startedCalls.first?.0 == "book1")
        #expect(progress.startedCalls.first?.1 == 2000)
    }
}

@Suite("Sleep timer firing")
@MainActor
struct SleepTimerFiringTests {
    @Test func firedEventFadesPausesAndCompletes() async throws {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let sleep = FakeSleepTiming()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        // `.zero` fade delay: exercise the fade logic without its ~3 s of real wall-clock
        // sleeps, which otherwise flake against the awaitUntil deadline under CI load.
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: sleep,
            engine: engine, fadeStepDelay: .zero)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        sleep.emitFired()
        await sleep.waitForFadeCompleted()

        #expect(await engine.didPause)
        #expect(sleep.fadeCompletedCount == 1)
        #expect(coordinator.isPlaying == false)
    }
}

@Suite("Save current position")
@MainActor
struct SaveCurrentPositionTests {
    @Test func saveCurrentPositionPersistsImmediately() async throws {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 4321, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        await coordinator.saveCurrentPosition()

        #expect(progress.savedNow.contains { $0.0 == "book1" })
    }

    @Test func saveCurrentPositionNoopsWhenIdle() async {
        let progress = FakeProgressReporting()
        let coordinator = PlayerCoordinator(
            preparer: FakePlaybackPreparing(), progress: progress,
            sleep: FakeSleepTiming(), engine: FakePlaybackEngine())
        await coordinator.saveCurrentPosition()
        #expect(progress.savedNow.isEmpty)
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

    private func makeCoordinator() -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting) {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine, notificationCenter: center)
        return (coordinator, engine, progress)
    }

    @Test func routeChangeOldDeviceUnavailablePauses() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        center.post(
            name: AVAudioSession.routeChangeNotification, object: nil,
            userInfo: [AVAudioSessionRouteChangeReasonKey:
                NSNumber(value: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue)])

        await engine.waitUntilPaused()
        #expect(await engine.didPause)
    }

}

// Each test instance owns a private `NotificationCenter` and injects it into its coordinator, so an
// interruption post here reaches ONLY this test's coordinator — never a sibling test's, and never a
// concurrently-running suite's (which was the `PlayerSwitchPathTests` cross-suite flake: a `.began`
// post pausing a coordinator that was mid-`.buffering`). `.serialized` is kept as belt-and-suspenders.
@Suite("Audio-session interruption handling", .serialized)
@MainActor
struct AudioSessionInterruptionTests {
    private let center = NotificationCenter()

    private func makeCoordinator() -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting) {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine, notificationCenter: center)
        return (coordinator, engine, progress)
    }

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
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        postInterruptionBegan()
        await engine.waitUntilPaused()

        #expect(await engine.didPause)
        #expect(coordinator.isPlaying == false)
        // `>= 1`, not `== 1`: every live coordinator observes `NotificationCenter.default`,
        // so a sibling suite's route-change/interruption post can add a pause here. Our own
        // code can't double-report one interruption — the `.pause` phase guard blocks the
        // second — so `>= 1` verifies the interruption was reported to KMP without over-
        // specifying a count the shared global center makes non-deterministic across suites.
        #expect(progress.pausedCalls.count >= 1)
    }

    @Test func interruptionEndedWithShouldResumeResumesAfterInterruptionPause() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        postInterruptionBegan()
        await engine.waitUntilPaused()
        postInterruptionEnded(shouldResume: true)
        await engine.waitForPlayCount(2)

        #expect(coordinator.isPlaying)
        let log = await engine.commandLog
        #expect(Array(log.suffix(2)) == ["activate", "play"])
        #expect(await engine.didActivateSession)
    }

    @Test func interruptionEndedWithoutShouldResumeStaysPaused() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        postInterruptionBegan()
        await engine.waitUntilPaused()
        postInterruptionEnded(shouldResume: false)

        await awaitUntil(timeout: .milliseconds(300)) { coordinator.isPlaying }
        #expect(coordinator.isPlaying == false)
        #expect(await engine.playCount == 1)
    }

    @Test func interruptionEndedDoesNotResumeUserInitiatedPause() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        coordinator.togglePlayback()   // user pauses deliberately
        await engine.waitUntilPaused()
        postInterruptionEnded(shouldResume: true)

        await awaitUntil(timeout: .milliseconds(300)) { coordinator.isPlaying }
        #expect(coordinator.isPlaying == false)
        #expect(await engine.playCount == 1)
    }

    @Test func interruptionBeganWhileBufferingPauses() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        engine.emit(.statusChanged(.buffering))
        await awaitUntil { coordinator.isBuffering }

        postInterruptionBegan()
        await engine.waitUntilPaused()

        #expect(coordinator.isPlaying == false)
        #expect(coordinator.isBuffering == false)
    }

    @Test func stopSeversInterruptionObservation() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        await coordinator.stop()
        let pausesBefore = await engine.commandLog.filter { $0 == "pause" }.count

        postInterruptionBegan()
        await awaitUntil(timeout: .milliseconds(300)) {
            await engine.commandLog.filter { $0 == "pause" }.count > pausesBefore
        }
        #expect(await engine.commandLog.filter { $0 == "pause" }.count == pausesBefore)
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

@Suite("End of chapter")
@MainActor
struct EndOfChapterTests {
    @Test func chapterChangeNotifiesSleepTiming() async throws {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let sleep = FakeSleepTiming()
        let preparer = FakePlaybackPreparing()
        let chapters = [
            Chapter(id: "c0", title: "c0", duration: 1000, startTime: 0),
            Chapter(id: "c1", title: "c1", duration: 1000, startTime: 1000)
        ]
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: chapters,
            timeline: PreparedTimeline(totalDurationMs: 2000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 2000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: sleep,
            engine: engine)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        // Position now in chapter 1 → engine emits a position past the chapter boundary.
        engine.emit(.position(ms: 1500, rate: 1.0))
        await sleep.waitForChapterChange(to: 1)

        #expect(sleep.chapterChanges.contains(1))
    }
}

@Suite("Skip interval wiring")
@MainActor
struct SkipIntervalTests {
    private func makeCoordinator(
        skipIntervals: FakeSkipIntervalProviding? = nil
    ) -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting) {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 600_000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 600_000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine,
            skipIntervals: skipIntervals)
        return (coordinator, engine, progress)
    }

    /// With no override, forward and backward use their *distinct* default intervals
    /// (30 / 10) — proving each direction reads its own setting-backed value rather
    /// than the old shared hardcoded amount.
    @Test func defaultForwardAndBackwardUseDistinctIntervals() async {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        // Anchor the position tracker at 60 s so both skips land on a positive position.
        // `rate: 0` (paused) is deliberate: a positive rate starts the `CADisplayLink`, whose
        // per-frame interpolation advances `bookPositionMs` past 60000 on the next frame — the
        // `== 60000` poll could then never settle and the test would hang. A paused sample holds
        // the position exactly, which is all this skip-math anchor needs.
        engine.emit(.position(ms: 60000, rate: 0.0))
        await awaitUntil { coordinator.bookPositionMs == 60000 }

        // 60 s + 30 s default forward = 90 s. A skip is a seek, so it splits the span (onSeek),
        // not a plain position update.
        coordinator.skipForward()
        await progress.waitForSeek(bookId: "book1", afterMs: 90000)
        #expect(progress.seeks.contains { $0.0 == "book1" && $0.2 == 90000 })

        // 60 s − 10 s default backward = 50 s (distinct from the forward interval).
        coordinator.skipBackward()
        await progress.waitForSeek(bookId: "book1", afterMs: 50000)
        #expect(progress.seeks.contains { $0.0 == "book1" && $0.2 == 50000 })
    }

    /// The provider's seeded values flow into the coordinator's observable surface.
    @Test func seededIntervalsApplyToObservableSurface() async {
        let skip = FakeSkipIntervalProviding(initialForward: 45, initialBackward: 15)
        let (coordinator, _, _) = makeCoordinator(skipIntervals: skip)
        await awaitUntil { coordinator.skipForwardSec == 45 && coordinator.skipBackwardSec == 15 }
        #expect(coordinator.skipForwardSec == 45)
        #expect(coordinator.skipBackwardSec == 15)
    }

    /// A live change emitted by the provider (e.g. the user picks a new interval on the
    /// Settings screen mid-session) propagates to the coordinator's interval *without* a
    /// rebuild — write → Flow emits → coordinator updates. This is the regression the
    /// repo-flow wiring exists to prevent. Kept playback-free so it only exercises the
    /// observation seam, not the full app start path.
    @Test func liveChangePropagatesToObservableSurface() async {
        let skip = FakeSkipIntervalProviding(initialForward: 30, initialBackward: 10)
        let (coordinator, _, _) = makeCoordinator(skipIntervals: skip)
        await awaitUntil { coordinator.skipForwardSec == 30 && coordinator.skipBackwardSec == 10 }

        // User changes the intervals after construction (mid-session, no rebuild).
        skip.emitForward(45)
        skip.emitBackward(20)
        await awaitUntil { coordinator.skipForwardSec == 45 && coordinator.skipBackwardSec == 20 }
        #expect(coordinator.skipForwardSec == 45)
        #expect(coordinator.skipBackwardSec == 20)
    }

    /// After a live change, the next skip honors the *new* interval. Together with
    /// `liveChangePropagatesToObservableSurface` this proves the full chain: a Settings
    /// write updates the interval and the very next skip uses it (45 s → 105 s, not 30 s → 90 s).
    @Test func nextSkipAfterLiveChangeUsesNewInterval() async {
        let skip = FakeSkipIntervalProviding(initialForward: 30, initialBackward: 10)
        let (coordinator, engine, progress) = makeCoordinator(skipIntervals: skip)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        await awaitUntil { coordinator.skipForwardSec == 30 }

        skip.emitForward(45)
        await awaitUntil { coordinator.skipForwardSec == 45 }

        // Anchor position at 60 s, then the next skip uses 45 s → 105 s.
        // `rate: 0` (paused) holds the position exactly; a positive rate would start the
        // `CADisplayLink` and interpolate past 60000, leaving the `== 60000` poll to hang.
        engine.emit(.position(ms: 60000, rate: 0.0))
        await awaitUntil { coordinator.bookPositionMs == 60000 }
        coordinator.skipForward()
        await progress.waitForSeek(bookId: "book1", afterMs: 105_000)
        #expect(progress.seeks.contains { $0.0 == "book1" && $0.2 == 105_000 })
    }
}

@Suite("Seek persistence")
@MainActor
struct SeekPersistenceTests {
    @Test func seekReportsNewPosition() async throws {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        // A seek splits the listening span; the reporter's onSeek both persists the new position
        // and records the split (before → after).
        coordinator.seekTo(positionMs: 30000)
        await progress.waitForSeek(bookId: "book1", afterMs: 30000)

        #expect(progress.seeks.contains { $0.0 == "book1" && $0.2 == 30000 })
    }
}

@Suite("Seam value types")
struct SeamValueTypeTests {
    @Test func preparedPlaybackHoldsTimeline() {
        let prepared = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil,
            resumeSpeed: 1.0, resumePositionMs: 0,
            chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 1000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 1000, startOffsetMs: 0)
            ])
        )
        #expect(prepared.timeline.files.count == 1)
    }
}

@Suite("Stop deactivates session")
@MainActor
struct StopDeactivatesSessionTests {
    private func makeCoordinator(_ engine: FakePlaybackEngine) -> PlayerCoordinator {
        PlayerCoordinator(
            preparer: FakePlaybackPreparing(), progress: FakeProgressReporting(),
            sleep: FakeSleepTiming(), engine: engine)
    }

    @Test func stopDeactivatesAudioSession() async throws {
        let engine = FakePlaybackEngine()
        let coordinator = makeCoordinator(engine)
        await coordinator.stop()
        #expect(await engine.didDeactivateSession)
    }

    /// The teardown invariant `stop()` exists to guarantee: the audio session is
    /// deactivated *before* the engine is released, and both complete before `stop()`
    /// returns (so the coordinator can't drop with the session still active).
    @Test func stopDeactivatesSessionBeforeReleasingEngine() async throws {
        let engine = FakePlaybackEngine()
        let coordinator = makeCoordinator(engine)
        await coordinator.stop()
        #expect(await engine.teardownOrder == ["deactivate", "release"])
        #expect(await engine.didRelease)
    }
}

@Suite("Document provider wiring")
@MainActor
struct DocumentProviderTests {
    private func makeCoordinator(documentProvider: FakeBookDocumentProviding) -> PlayerCoordinator {
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        return PlayerCoordinator(
            preparer: preparer, progress: FakeProgressReporting(), sleep: FakeSleepTiming(),
            engine: FakePlaybackEngine(),
            documentProvider: documentProvider
        )
    }

    @Test func firstPdfDocIdIsSetAfterPlayWhenFakeReturnsPdfId() async {
        let docProvider = FakeBookDocumentProviding()
        docProvider.pdfDocId = "doc-42"
        let coordinator = makeCoordinator(documentProvider: docProvider)
        coordinator.play(bookId: "book1")
        await awaitUntil { coordinator.firstPdfDocId == "doc-42" }
        #expect(coordinator.firstPdfDocId == "doc-42")
    }

    @Test func firstPdfDocIdRemainsNilWhenFakeReturnsNil() async {
        let docProvider = FakeBookDocumentProviding()
        docProvider.pdfDocId = nil
        let coordinator = makeCoordinator(documentProvider: docProvider)
        coordinator.play(bookId: "book1")
        // Allow the async task to settle, then confirm nil.
        try? await Task.sleep(for: .milliseconds(100))
        #expect(coordinator.firstPdfDocId == nil)
    }

    @Test func openCurrentBookPdfSetsDocumentToOpenWithFakePath() async {
        let docProvider = FakeBookDocumentProviding()
        docProvider.pdfDocId = "doc-99"
        docProvider.localPath = "/books/mybook.pdf"
        let coordinator = makeCoordinator(documentProvider: docProvider)
        coordinator.play(bookId: "book1")
        await awaitUntil { coordinator.firstPdfDocId == "doc-99" }

        coordinator.openCurrentBookPdf()
        await awaitUntil { coordinator.documentToOpen != nil }

        #expect(coordinator.documentToOpen?.url == URL(fileURLWithPath: "/books/mybook.pdf"))
    }
}

@Suite("Playback lifecycle")
@MainActor
struct PlaybackLifecycleTests {
    private func makeCoordinator() -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting) {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine)
        return (coordinator, engine, progress)
    }

    /// A full play → pause → stop journey leaves the engine and `phase` consistent:
    /// playing after play, paused after toggle, torn down (and hidden) after stop.
    @Test func playPauseStopLeavesConsistentState() async throws {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        // Anchor on the coordinator's own "started" callback, not `engine.play()`: the
        // coordinator sets `phase = .playing` *then* reports started, so awaiting the engine
        // command would race the phase write and read `isPlaying` before it flips.
        await progress.waitForStarted(bookId: "book1")
        // The coordinator starts in `.buffering` and is promoted to `.playing` by the engine's
        // first "playing" status event (RC-3); await that promotion rather than asserting it
        // the instant `onPlaybackStarted` lands.
        await awaitUntil { coordinator.isPlaying }
        #expect(coordinator.isPlaying)
        #expect(coordinator.isVisible)

        coordinator.togglePlayback()
        await engine.waitUntilPaused()
        #expect(coordinator.isPlaying == false)

        await coordinator.stop()
        #expect(await engine.didDeactivateSession)
        #expect(await engine.didRelease)
    }

    /// `stop()` tears down engine observation (`bridge.cancelAll()`), so an engine event
    /// arriving after teardown must not mutate `phase`. A leaked subscription would flip
    /// it to `.error`; we poll briefly so a real leak is caught fast while a healthy run
    /// pays only a small bounded cost.
    @Test func stopSeversEngineObservation() async throws {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        await coordinator.stop()
        engine.emit(.failed(message: "late event after stop"))

        await awaitUntil(timeout: .milliseconds(300)) {
            if case .error = coordinator.phase { return true }
            return false
        }
        if case .error = coordinator.phase {
            Issue.record("stop() left engine observation live — a late event mutated phase")
        }
    }
}
