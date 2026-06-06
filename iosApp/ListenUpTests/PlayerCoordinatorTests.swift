import Testing
import AVFoundation
@testable import ListenUp
@preconcurrency import Shared

/// Polls `condition` until true or the timeout elapses. Replaces fixed `Task.sleep`
/// waits so tests finish as soon as the async work completes and only fail if it
/// genuinely never does — deterministic, no wall-clock floor.
@MainActor
func awaitUntil(
    timeout: Duration = .seconds(8),
    pollInterval: Duration = .milliseconds(20),
    _ condition: () async -> Bool
) async {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if await condition() { return }
        try? await Task.sleep(for: pollInterval)
    }
}

@Suite("ChapterMath")
struct PlayerCoordinatorTests {
    private func chapter(_ id: String, start: Int64, duration: Int64) -> Chapter_ {
        Chapter_(id: id, title: id, duration: duration, startTime: start)
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
            bookTitle: "T", bookAuthor: "A", coverPath: nil,
            resumeSpeed: 1.5, resumePositionMs: 2000,
            chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)
            ])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: sleep,
            engine: engine, coverProvider: FakeBookCoverProviding()
        )
        return (coordinator, engine, progress)
    }

    @Test func playLoadsAndStartsEngineAtResumePosition() async throws {
        let (coordinator, engine, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await awaitUntil { await engine.didPlay }
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
            bookTitle: "T", bookAuthor: "A", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        // `.zero` fade delay: exercise the fade logic without its ~3 s of real wall-clock
        // sleeps, which otherwise flake against the awaitUntil deadline under CI load.
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: sleep,
            engine: engine, coverProvider: FakeBookCoverProviding(), fadeStepDelay: .zero)
        coordinator.play(bookId: "book1")
        await awaitUntil { await engine.didPlay }

        sleep.emitFired()
        await awaitUntil { await engine.didPause && sleep.fadeCompletedCount == 1 }

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
            bookTitle: "T", bookAuthor: "A", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 4321, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine, coverProvider: FakeBookCoverProviding())
        coordinator.play(bookId: "book1")
        await awaitUntil { await engine.didPlay }

        await coordinator.saveCurrentPosition()

        #expect(progress.savedNow.contains { $0.0 == "book1" })
    }

    @Test func saveCurrentPositionNoopsWhenIdle() async {
        let progress = FakeProgressReporting()
        let coordinator = PlayerCoordinator(
            preparer: FakePlaybackPreparing(), progress: progress,
            sleep: FakeSleepTiming(), engine: FakePlaybackEngine(),
            coverProvider: FakeBookCoverProviding())
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

@Suite("End of chapter")
@MainActor
struct EndOfChapterTests {
    @Test func chapterChangeNotifiesSleepTiming() async throws {
        let engine = FakePlaybackEngine()
        let sleep = FakeSleepTiming()
        let preparer = FakePlaybackPreparing()
        let chapters = [
            Chapter_(id: "c0", title: "c0", duration: 1000, startTime: 0),
            Chapter_(id: "c1", title: "c1", duration: 1000, startTime: 1000)
        ]
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: chapters,
            timeline: PreparedTimeline(totalDurationMs: 2000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 2000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: FakeProgressReporting(), sleep: sleep,
            engine: engine, coverProvider: FakeBookCoverProviding())
        coordinator.play(bookId: "book1")
        await awaitUntil { await engine.didPlay }

        // Position now in chapter 1 → engine emits a position past the chapter boundary.
        engine.emit(.position(ms: 1500, rate: 1.0))
        await awaitUntil { sleep.chapterChanges.contains(1) }

        #expect(sleep.chapterChanges.contains(1))
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
            bookTitle: "T", bookAuthor: "A", coverPath: nil, resumeSpeed: 1.0,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine, coverProvider: FakeBookCoverProviding())
        coordinator.play(bookId: "book1")
        await awaitUntil { await engine.didPlay }

        coordinator.seekTo(positionMs: 30000)
        await awaitUntil { progress.positionUpdates.contains { $0.0 == "book1" && $0.1 == 30000 } }

        #expect(progress.positionUpdates.contains { $0.0 == "book1" && $0.1 == 30000 })
    }
}

@Suite("Seam value types")
struct SeamValueTypeTests {
    @Test func preparedPlaybackHoldsTimeline() {
        let prepared = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", coverPath: nil,
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
    @Test func stopDeactivatesAudioSession() async throws {
        let engine = FakePlaybackEngine()
        let coordinator = PlayerCoordinator(
            preparer: FakePlaybackPreparing(), progress: FakeProgressReporting(),
            sleep: FakeSleepTiming(), engine: engine, coverProvider: FakeBookCoverProviding())
        coordinator.stop()
        await awaitUntil { await engine.didDeactivateSession }
        #expect(await engine.didDeactivateSession)
    }
}
