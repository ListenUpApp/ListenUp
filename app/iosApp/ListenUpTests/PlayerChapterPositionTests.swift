import Testing
@testable import ListenUp
import Shared

@Suite("Chapter position")
@MainActor
struct PlayerChapterPositionTests {
    /// The coordinator's chapter index (the lock screen's re-stamp at a boundary) tracks the COARSE
    /// position, so it moves ~1×/s instead of on every display frame.
    @Test func chapterIndexFollowsTheCoarsePosition() async throws {
        let chapters = [
            Chapter(id: "c0", title: "c0", duration: 1000, startTime: 0, partTitle: nil, bookTitle: nil),
            Chapter(id: "c1", title: "c1", duration: 9000, startTime: 1000, partTitle: nil, bookTitle: nil)
        ]
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", .fixture(resumePositionMs: 3750, durationMs: 10000, chapters: chapters))

        // 3750 floors to 3000 on the coarse clock, inside chapter 1 (which starts at 1000).
        #expect(harness.coordinator.chapterIndex == 1)
        #expect(harness.coordinator.displayPositionMs == 3000)
        #expect(harness.coordinator.chapterTitle == "c1")
    }
}
