import Testing
@testable import ListenUp
import Shared

@Suite("Chapter position")
@MainActor
struct PlayerChapterPositionTests {
    /// The mini player's "time left in chapter" and its progress hairline read the COARSE
    /// chapter position, so they re-render ~1×/s instead of on every display frame.
    @Test func displayChapterPositionIsTheCoarsePositionWithinTheChapter() async throws {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        let chapters = [
            Chapter(id: "c0", title: "c0", duration: 1000, startTime: 0, partTitle: nil, bookTitle: nil),
            Chapter(id: "c1", title: "c1", duration: 9000, startTime: 1000, partTitle: nil, bookTitle: nil)
        ]
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumeBoostDb: 0, measuredGainDb: nil, normalizationGainDb: nil,
            resumePositionMs: 3750, chapters: chapters,
            timeline: PreparedTimeline(totalDurationMs: 10000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 10000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine)
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        // 3750 floors to 3000 on the coarse clock; chapter 1 starts at 1000.
        #expect(coordinator.chapterIndex == 1)
        #expect(coordinator.displayChapterPositionMs == 2000)
    }
}
