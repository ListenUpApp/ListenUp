import Testing
@testable import ListenUp
import Shared

/// A sleep timer belongs to the book it was set on.
///
/// Its own file because `PlayerCoordinatorTests` is at SwiftLint's 800-line ceiling, and because
/// this is one coherent promise rather than another coordinator detail.
@Suite("Sleep timer book scoping")
@MainActor
struct SleepTimerBookScopingTests {
    /// ⛔ The bug this pins: `PlayerCoordinator` forwarded only `onChapterChanged`, never
    /// `onBookChanged`, so a sleep timer armed on one book survived into the next — an
    /// end-of-chapter timer faded out the NEW book at its first chapter turn, and a duration timer
    /// kept counting across books. `SleepTimerManager.onBookChanged`'s own KDoc names why that is
    /// the worst case: it happens while the listener is asleep and cannot correct it.
    ///
    /// Web carried the identical defect and was fixed the same way, at the moment the book changes
    /// rather than from an observer — reporting later would clear the end-of-chapter baseline with
    /// nothing left to restore it.
    @Test func switchingBooksNotifiesSleepTiming() async throws {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let sleep = FakeSleepTiming()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: nil, resumeSpeed: 1.0,
            resumeBoostDb: 0, measuredGainDb: nil, normalizationGainDb: nil,
            resumePositionMs: 0, chapters: [],
            timeline: PreparedTimeline(totalDurationMs: 2000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 2000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: sleep,
            engine: engine)

        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        #expect(sleep.bookChanges == ["book1"])

        coordinator.play(bookId: "book2")
        await progress.waitForStarted(bookId: "book2")

        // Reported before the prepare completes, so the timer is cancelled while the new book's
        // chapters are still loading rather than after they have re-established a baseline.
        #expect(sleep.bookChanges == ["book1", "book2"])
    }
}
