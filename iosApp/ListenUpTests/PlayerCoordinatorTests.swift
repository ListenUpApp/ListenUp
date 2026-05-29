import Testing
@testable import ListenUp
@preconcurrency import Shared

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
            chapter("c2", start: 3000, duration: 500),
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
    private func makeCoordinator() -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting, FakeSleepTiming, FakePlaybackPreparing) {
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
        return (coordinator, engine, progress, sleep, preparer)
    }

    @Test func playLoadsAndStartsEngineAtResumePosition() async throws {
        let (coordinator, engine, progress, _, _) = makeCoordinator()
        coordinator.play(bookId: "book1")
        try await Task.sleep(for: .milliseconds(50))
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
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: sleep,
            engine: engine, coverProvider: FakeBookCoverProviding())
        coordinator.play(bookId: "book1")
        try await Task.sleep(for: .milliseconds(50))

        sleep.emitFired()
        try await Task.sleep(for: .milliseconds(3500))

        #expect(await engine.didPause)
        #expect(sleep.fadeCompletedCount == 1)
        #expect(coordinator.isPlaying == false)
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
