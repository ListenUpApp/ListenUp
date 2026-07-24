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
    ) -> (PlayerCoordinator, FakePlaybackEngine, FakeProgressReporting) {
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
            engine: engine)
        return (coordinator, engine, progress)
    }

    /// RC-1(a): switching clears the cover/chapters/title *synchronously*, before the async
    /// prepare — so the UI never renders the outgoing book's cover against the incoming book's id.
    @Test func switchingClearsMetadataSynchronously() async {
        let (coordinator, _, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        #expect(coordinator.coverPath == "/covers/a.jpg")
        #expect(!coordinator.chapters.isEmpty)

        coordinator.play(bookId: "book2")
        // No `await` before these reads: the reset is synchronous, so it has happened while the
        // incoming book's prepare has not yet run.
        #expect(coordinator.coverPath == nil)
        #expect(coordinator.chapters.isEmpty)
        #expect(coordinator.bookTitle.isEmpty)
        #expect(coordinator.currentBookId == "book2")
    }

    /// RC-4: a rapid A→B switch supersedes A's prepare before it completes; only B ends up
    /// loaded, and A never reports a phantom start.
    @Test func rapidSwitchLandsOnLastBookOnly() async {
        let (coordinator, _, progress) = makeCoordinator()
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
        let (coordinator, _, progress) = makeCoordinator()
        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")

        coordinator.play(bookId: "book2")
        // The outgoing save is issued synchronously in `play`, before the new prepare.
        #expect(progress.pausedCalls.contains { $0.0 == "book1" })

        await progress.waitForStarted(bookId: "book2")
        #expect(coordinator.currentBookId == "book2")
    }

    /// Honest buffering UI: the instant prepare resolves, the player shows the real book — its
    /// real duration and a buffering state — instead of sitting at "0m / preparing" for the whole
    /// (possibly slow, streaming) load. So while the engine's load is still in flight, phase is
    /// already `.buffering` carrying the duration, and the mini-player is visible with it.
    @Test func showsRealDurationAndBuffersWhileLoadInFlight() async {
        let (coordinator, engine, progress) = makeCoordinator()
        await engine.setBlockLoad(true)   // hold the load open to observe the intermediate state

        coordinator.play(bookId: "book1")
        await engine.waitForLoadEntered()  // prepare done, engine.load called and now suspended

        // Mid-load the UI is already honest — real duration, buffering, visible — not 0m/paused.
        #expect(coordinator.bookDurationMs == 60000)
        #expect(coordinator.isBuffering)
        #expect(coordinator.isVisible)

        await engine.releaseLoad()
        await progress.waitForStarted(bookId: "book1")
    }

    /// RC-3: a fresh load enters `.buffering` and is promoted to `.playing` only by the engine's
    /// first real "playing" status event — never optimistically. With the fake's auto-ready
    /// suppressed, the coordinator must sit in `.buffering` after start and only reach `.playing`
    /// once an explicit `.ready` arrives.
    @Test func freshLoadEntersBufferingThenPlaying() async {
        let (coordinator, engine, progress) = makeCoordinator()
        await engine.setAutoReadyOnPlay(false)

        coordinator.play(bookId: "book1")
        await progress.waitForStarted(bookId: "book1")
        // Started, but not yet playing — the engine hasn't reported audio advancing.
        #expect(coordinator.isBuffering)
        #expect(!coordinator.isPlaying)

        engine.emit(.statusChanged(.ready))   // the first real "playing" event
        await awaitObservation { coordinator.isPlaying }
        #expect(coordinator.isPlaying)
        #expect(!coordinator.isBuffering)
    }

    /// `stop()` mid-load must supersede the in-flight load so it can't resurrect playback on a
    /// released engine — no phantom `onPlaybackStarted`, no `.playing` after teardown. (`Task.cancel()`
    /// alone doesn't interrupt the load's non-cancellation-checking awaits, so `stop()` bumps the epoch.)
    @Test func stopDuringLoadDoesNotResurrectPlayback() async {
        let (coordinator, engine, progress) = makeCoordinator()
        await engine.setBlockLoad(true)

        coordinator.play(bookId: "book1")
        await engine.waitForLoadEntered()   // load is in flight, blocked

        await coordinator.stop()            // teardown must supersede the in-flight load
        await engine.releaseLoad()          // let the (now superseded) load resolve
        await Task.yield()                  // give the superseded continuation a chance to (wrongly) start

        #expect(!progress.startedCalls.contains { $0.0 == "book1" })
        if case .playing = coordinator.phase {
            Issue.record("phase resurrected to .playing after stop()")
        }
    }

    /// Never stranded: a load failure keeps the player VISIBLE with the error message, so the
    /// existing `.error`-retry (`togglePlayback`) is actually reachable rather than hidden behind
    /// a vanished player.
    @Test func errorStateKeepsPlayerVisibleWithMessage() async {
        let (coordinator, engine, _) = makeCoordinator()
        await engine.setLoadShouldFail(true)
        coordinator.play(bookId: "book1")
        await awaitObservation { if case .error = coordinator.phase { return true }; return false }

        #expect(coordinator.isVisible)
        #expect(coordinator.isErrored)
        #expect(coordinator.errorMessage != nil)
    }

    /// The user can DISMISS an errored player back to idle (hidden) — otherwise a failed offline
    /// load would leave an undismissable error bar reserving space over every tab.
    @Test func dismissErrorReturnsToIdleAndHides() async {
        let (coordinator, engine, _) = makeCoordinator()
        await engine.setLoadShouldFail(true)
        coordinator.play(bookId: "book1")
        await awaitObservation { if case .error = coordinator.phase { return true }; return false }
        #expect(coordinator.isVisible)   // errored → still visible (for the inline retry)

        coordinator.dismissError()
        #expect(!coordinator.isVisible)  // dismissed → hidden
        #expect(!coordinator.isErrored)
        guard case .idle = coordinator.phase else {
            Issue.record("expected .idle after dismissError, got \(coordinator.phase)")
            return
        }
    }

    /// RC-5: a load failure surfaces `.error`; toggling the errored book retries it rather than
    /// no-oping, so the user is never stranded on a dead player.
    @Test func errorStateIsRecoverableByToggle() async {
        let (coordinator, engine, progress) = makeCoordinator()
        await engine.setLoadShouldFail(true)

        coordinator.play(bookId: "book1")
        await awaitObservation {
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
        await awaitObservation { coordinator.isPlaying }
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
