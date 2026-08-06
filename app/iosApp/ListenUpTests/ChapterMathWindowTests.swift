import Testing
import Shared
@testable import ListenUp

/// The chapter-scoped window the lock screen, Control Center and (later) CarPlay present, plus the
/// single translation between window and book coordinates.
///
/// Split from `PlayerCoordinatorTests` so the coordinate-space rules have one obvious home — and
/// because conflating those two spaces is the defect class that produced #1251 on Android.
@Suite("ChapterMath.Window")
struct ChapterMathWindowTests {
    private func chapter(_ id: String, start: Int64, duration: Int64) -> Chapter {
        Chapter(id: id, title: id, duration: duration, startTime: start)
    }

    // MARK: - Now-Playing window

    private var threeChapters: [Chapter] {
        [
            chapter("c0", start: 0, duration: 1000),
            chapter("c1", start: 1000, duration: 2000),
            chapter("c2", start: 3000, duration: 500)
        ]
    }

    @Test func windowSpansTheContainingChapter() {
        let window = ChapterMath.window(forPositionMs: 1500, in: threeChapters, bookDurationMs: 3500)
        #expect(window.index == 1)
        #expect(window.startMs == 1000)
        #expect(window.durationMs == 2000)
        #expect(window.chapterCount == 3)
    }

    /// A window ends where the NEXT chapter starts, not at `start + duration`. Chapters carry no
    /// stored end, so trusting `duration` leaves gaps when it disagrees with the gap to the next
    /// chapter — and a gap is a position the lock screen cannot express. Matches the Android
    /// `currentChapterWindow` rule so the two platforms cannot drift.
    @Test func windowEndsWhereTheNextChapterBegins() {
        let ragged = [
            chapter("c0", start: 0, duration: 900),   // stored duration undershoots the 1000ms gap
            chapter("c1", start: 1000, duration: 2000)
        ]
        let window = ChapterMath.window(forPositionMs: 950, in: ragged, bookDurationMs: 3000)
        #expect(window.index == 0)
        #expect(window.durationMs == 1000)
    }

    @Test func lastChapterWindowEndsAtTheBookDuration() {
        let window = ChapterMath.window(forPositionMs: 3200, in: threeChapters, bookDurationMs: 3800)
        #expect(window.index == 2)
        #expect(window.startMs == 3000)
        #expect(window.durationMs == 800)
    }

    /// Chapterless books present the whole book as one window — the same fallback Android uses,
    /// so the lock screen degrades to book-relative times rather than showing nothing.
    @Test func chapterlessBookIsOneWholeBookWindow() {
        let window = ChapterMath.window(forPositionMs: 1234, in: [], bookDurationMs: 9000)
        #expect(window.index == -1)
        #expect(window.startMs == 0)
        #expect(window.durationMs == 9000)
        #expect(window.chapterCount == 0)
    }

    // MARK: - Seek back-translation (window-relative -> book-relative)

    @Test func seekTargetTranslatesWindowPositionToBookPosition() {
        let window = ChapterMath.window(forPositionMs: 1500, in: threeChapters, bookDurationMs: 3500)
        #expect(ChapterMath.bookPosition(forWindowPositionMs: 500, in: window) == 1500)
    }

    /// The scrubber cannot express a position outside its own window, so anything beyond it is a
    /// bug upstream, not an instruction to leave the chapter. Clamp rather than obey.
    @Test func seekTargetClampsToTheWindow() {
        let window = ChapterMath.window(forPositionMs: 1500, in: threeChapters, bookDurationMs: 3500)
        #expect(ChapterMath.bookPosition(forWindowPositionMs: -50, in: window) == 1000)
        #expect(ChapterMath.bookPosition(forWindowPositionMs: 99_999, in: window) == 3000)
    }

    @Test func seekTargetOnAChapterlessBookIsTheBookPositionItself() {
        let window = ChapterMath.window(forPositionMs: 0, in: [], bookDurationMs: 9000)
        #expect(ChapterMath.bookPosition(forWindowPositionMs: 4321, in: window) == 4321)
    }

    @Test func windowPositionIsTheInverseOfTheSeekTranslation() {
        let window = ChapterMath.window(forPositionMs: 1500, in: threeChapters, bookDurationMs: 3500)
        #expect(ChapterMath.windowPosition(forBookPositionMs: 1500, in: window) == 500)
        // Round-trips: book -> window -> book lands where it started.
        let windowPos = ChapterMath.windowPosition(forBookPositionMs: 2200, in: window)
        #expect(ChapterMath.bookPosition(forWindowPositionMs: windowPos, in: window) == 2200)
    }

    @Test func windowPositionClampsToTheWindow() {
        let window = ChapterMath.window(forPositionMs: 1500, in: threeChapters, bookDurationMs: 3500)
        #expect(ChapterMath.windowPosition(forBookPositionMs: 0, in: window) == 0)
        #expect(ChapterMath.windowPosition(forBookPositionMs: 99_999, in: window) == 2000)
    }
}
