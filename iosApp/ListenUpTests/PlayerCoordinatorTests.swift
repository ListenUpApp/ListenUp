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
