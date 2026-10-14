import Testing
import AVFoundation
@testable import ListenUp
import Shared

/// Coverage for the engine's book-switch path (a `.load` while another book is loaded or loading):
/// the metadata resets synchronously (RC-1), a superseded load can't clobber the new book (RC-4), and
/// the outgoing book's place is saved. Which book is loaded, retry and dismiss are the shared
/// player's (`PlayerSessionPlayTest`, `PlayerSessionErrorsTest`).
@Suite("Player switch path")
@MainActor
struct PlayerSwitchPathTests {
    private let chapters = [
        Chapter(id: "c0", title: "c0", duration: 1000, startTime: 0, partTitle: nil, bookTitle: nil)
    ]

    private func book(cover: String? = "/covers/a.jpg") -> ListenUp.PreparedPlayback {
        .fixture(coverPath: cover, chapters: chapters)
    }

    /// RC-1(a): a switch replaces the outgoing book's lock-screen metadata synchronously.
    @Test func switchingReplacesMetadataSynchronously() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", book())
        #expect(harness.coordinator.coverPath == "/covers/a.jpg")
        #expect(!harness.coordinator.chapterRows.isEmpty)

        await harness.engine.setBlockLoad(true)
        await harness.run(.load(bookId: "book2", prepared: book(cover: nil)))
        #expect(harness.coordinator.coverPath == nil)
        #expect(harness.coordinator.currentBookId == "book2")
        await harness.engine.releaseLoad()
    }

    /// RC-4: a rapid A→B switch supersedes A's load before it completes; only B ends up loaded, and A
    /// never reports a phantom start. (Its Kotlin twin, `A → B → A`, is in `PlayerSessionPlayTest`.)
    @Test func rapidSwitchLandsOnLastBookOnly() async {
        let harness = PlayerHarness()
        harness.instructions.send(.load(bookId: "book1", prepared: book()))
        harness.instructions.send(.load(bookId: "book2", prepared: book()))
        await harness.progress.waitForStarted(bookId: "book2")

        #expect(harness.coordinator.currentBookId == "book2")
        #expect(harness.progress.startedCalls.contains { $0.0 == "book2" })
        #expect(!harness.progress.startedCalls.contains { $0.0 == "book1" })
    }

    /// A switch away from a playing book saves the outgoing book's place first.
    @Test func switchingFromPlayingBookSavesOutgoingPosition() async {
        let harness = PlayerHarness()
        await harness.loadAndStart("book1", book())

        await harness.run(.load(bookId: "book2", prepared: book()))
        // The outgoing save is issued synchronously by the load, before the new engine load.
        #expect(harness.progress.pausedCalls.contains { $0.0 == "book1" })

        await harness.progress.waitForStarted(bookId: "book2")
        #expect(harness.coordinator.currentBookId == "book2")
    }

    /// Honest buffering: while the engine's load is still in flight, the book is already held with
    /// its real duration, and the shared player has been told it is buffering.
    @Test func showsRealDurationAndBuffersWhileLoadInFlight() async {
        let harness = PlayerHarness()
        await harness.engine.setBlockLoad(true)

        await harness.run(.load(bookId: "book1", prepared: book()))
        await harness.engine.waitForLoadEntered()

        #expect(harness.coordinator.bookDurationMs == 60000)
        #expect(harness.coordinator.isBuffering)
        #expect(harness.facts.phases.last == .buffering)

        await harness.engine.releaseLoad()
        await harness.progress.waitForStarted(bookId: "book1")
    }

    /// RC-3: a fresh load enters `.buffering` and is promoted to `.playing` only by the engine's
    /// first real "playing" status event — never optimistically.
    @Test func freshLoadEntersBufferingThenPlaying() async {
        let harness = PlayerHarness()
        await harness.engine.setAutoReadyOnPlay(false)

        await harness.loadAndStart("book1", book())
        #expect(harness.coordinator.isBuffering)
        #expect(!harness.coordinator.isPlaying)

        harness.engine.emit(.statusChanged(.ready))
        await awaitObservation { harness.coordinator.isPlaying }
        #expect(harness.coordinator.isPlaying)
        #expect(!harness.coordinator.isBuffering)
    }

    /// A stop mid-load must supersede the in-flight load so it can't resurrect playback on an
    /// unloaded engine — no phantom `onPlaybackStarted`, no `.playing` after teardown.
    @Test func stopDuringLoadDoesNotResurrectPlayback() async {
        let harness = PlayerHarness()
        await harness.engine.setBlockLoad(true)

        await harness.run(.load(bookId: "book1", prepared: book()))
        await harness.engine.waitForLoadEntered()

        await harness.run(.stop)
        await harness.engine.releaseLoad()
        await Task.yield()

        #expect(!harness.progress.startedCalls.contains { $0.0 == "book1" })
        #expect(!harness.coordinator.isPlaying)
        #expect(harness.coordinator.engineBook == nil)
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
