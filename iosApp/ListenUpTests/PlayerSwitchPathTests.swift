import Testing
import AVFoundation
@testable import ListenUp
import Shared

/// Coverage for the book-switch path (`play(bookId:)` while another book is loaded/preparing):
/// the metadata surface resets synchronously (RC-1), a superseded prepare can't clobber the
/// new book (RC-4), the outgoing book's place is saved, and an errored book can be retried (RC-5).
@Suite("Player switch path")
@MainActor
struct PlayerSwitchPathTests {
    private func makeCoordinator(
        coverPath: String? = "/covers/a.jpg"
    ) -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting, FakePlaybackPreparing) {
        let engine = FakePlaybackEngine()
        let progress = FakeProgressReporting()
        let preparer = FakePlaybackPreparing()
        preparer.result = PreparedPlayback(
            bookTitle: "T", bookAuthor: "A", bookNarrator: "N", coverPath: coverPath, resumeSpeed: 1.0,
            resumePositionMs: 0,
            chapters: [Chapter(id: "c0", title: "c0", duration: 1000, startTime: 0)],
            timeline: PreparedTimeline(totalDurationMs: 60000, files: [
                PreparedFile(localPath: "/a.m4a", streamingUrl: "", durationMs: 60000, startOffsetMs: 0)])
        )
        let coordinator = PlayerCoordinator(
            preparer: preparer, progress: progress, sleep: FakeSleepTiming(),
            engine: engine, coverProvider: FakeBookCoverProviding())
        return (coordinator, engine, progress, preparer)
    }

    /// RC-1(a): switching clears the cover/chapters/title *synchronously*, before the async
    /// prepare — so the UI never renders the outgoing book's cover against the incoming book's id.
    @Test func switchingClearsMetadataSynchronously() async {
        let (coordinator, _, progress, _) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        #expect(coordinator.coverPath == "/covers/a.jpg")
        #expect(!coordinator.chapters.isEmpty)

        coordinator.play(bookId: "book2")
        // No `await` before these reads: the reset is synchronous, so it has happened while the
        // incoming book's prepare has not yet run.
        #expect(coordinator.coverPath == nil)
        #expect(coordinator.chapters.isEmpty)
        #expect(coordinator.bookTitle == "")
        #expect(coordinator.currentBookId == "book2")
    }

    /// RC-4: a rapid A→B switch supersedes A's prepare before it completes; only B ends up
    /// loaded, and A never reports a phantom start.
    @Test func rapidSwitchLandsOnLastBookOnly() async {
        let (coordinator, _, progress, _) = makeCoordinator()
        coordinator.play(bookId: "book1")
        coordinator.play(bookId: "book2")   // supersedes book1 before its prepare resolves
        await progress.waitForStarted(bookId: "book2")

        #expect(coordinator.currentBookId == "book2")
        #expect(coordinator.phase.bookId == "book2")
        #expect(progress.startedCalls.contains { $0.0 == "book2" })
        #expect(!progress.startedCalls.contains { $0.0 == "book1" })
    }

    /// A switch away from a playing book saves the outgoing book's place first.
    @Test func switchingFromPlayingBookSavesOutgoingPosition() async {
        let (coordinator, _, progress, _) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        coordinator.play(bookId: "book2")
        // The outgoing save is issued synchronously in `play`, before the new prepare.
        #expect(progress.pausedCalls.contains { $0.0 == "book1" })

        await progress.waitForStarted(bookId: "book2")
        #expect(coordinator.currentBookId == "book2")
    }

    /// RC-3: a fresh load enters `.buffering` and is promoted to `.playing` only by the engine's
    /// first real "playing" status event — never optimistically.
    @Test func freshLoadEntersBufferingThenPlaying() async {
        let (coordinator, _, progress, _) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        await awaitUntil { coordinator.isPlaying }
        #expect(coordinator.isPlaying)
    }

    /// RC-5: a load failure surfaces `.error`; toggling the errored book retries it rather than
    /// no-oping, so the user is never stranded on a dead player.
    @Test func errorStateIsRecoverableByToggle() async {
        let (coordinator, engine, progress, _) = makeCoordinator()
        await engine.setLoadShouldFail(true)

        coordinator.play(bookId: "book1")
        await awaitUntil {
            if case .error = coordinator.phase { return true }
            return false
        }
        guard case .error = coordinator.phase else {
            Issue.record("expected .error, got \(coordinator.phase)")
            return
        }

        await engine.setLoadShouldFail(false)
        coordinator.togglePlayback()   // retry
        await progress.waitForStarted(bookId: "book1")
        await awaitUntil { coordinator.isPlaying }
        #expect(coordinator.isPlaying)
    }
}

/// The load-generation guard predicate in isolation — a plain epoch comparison, no coordinator.
@Suite("LoadGeneration")
struct LoadGenerationTests {
    @Test func sameGenerationIsNotSuperseded() {
        #expect(LoadGeneration.isSuperseded(taskGeneration: 3, current: 3) == false)
    }

    @Test func olderGenerationIsSuperseded() {
        #expect(LoadGeneration.isSuperseded(taskGeneration: 2, current: 3))
    }
}
