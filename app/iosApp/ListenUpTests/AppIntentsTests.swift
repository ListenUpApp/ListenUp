import AppIntents
import Testing
@testable import ListenUp
@testable import ListenUpActivityKit

/// Records which control method the intent invoked.
@MainActor
final class FakePlaybackController: PlaybackControlling {
    var toggled = false
    var skippedForward = false
    var skippedBackward = false
    var playedBookId: String?

    func togglePlayPause() { toggled = true }
    func skipForward() { skippedForward = true }
    func skipBackward() { skippedBackward = true }
    func playBook(id: String) { playedBookId = id }
}

/// Supplies a canned "last-played book id" for `ResumePlaybackIntent` routing tests.
@MainActor
final class FakeLastPlayedBookProvider: LastPlayedBookProviding {
    var bookId: String?
    init(bookId: String?) { self.bookId = bookId }
    func mostRecentBookId() async -> String? { bookId }
}

/// The whole App Intents surface, in **one** serialized suite — and it has to be one.
///
/// `@Dependency` resolves through the process-wide `AppDependencyManager`, keyed by the protocol
/// type. Its setter is `nonmutating`, which is the tell: `intent.playback = fake` on a `let` does
/// not write to that instance, it writes to a slot every `any PlaybackControlling` dependency in
/// the process shares. `dependencySlotIsProcessWideNotPerInstance` below proves it.
///
/// So two tests that each install their own fake cannot overlap in time, whichever suites they
/// live in. These tests used to sit in two files, `PlaybackIntentsTests` and `AppIntentsTests`,
/// each carrying `@Suite(.serialized)` — which orders tests *within* a suite and does nothing
/// between them. Under xcodebuild's parallel clones the two raced: one suite's fake replaced the
/// other's between its `intent.playback = fake` and its `perform()`, so the call landed on the
/// wrong fake and the assertion failed. It surfaced as
/// `skipBackwardIntentRoutesToSkipBackward()` failing in 0.135s on an unrelated CI PR.
///
/// ⛔ A new test that installs an App Intents dependency belongs in **this suite**, not a new
/// file. A second serialized suite reintroduces exactly the race this one exists to remove.
@MainActor
@Suite(.serialized)
struct AppIntentsTests {

    // MARK: - The constraint this file is shaped by

    /// Pins the reason everything lives in one suite: the dependency slot is shared, so a fake
    /// installed through one intent is what a *different* intent resolves.
    ///
    /// If Apple ever makes `@Dependency` storage per-instance this test fails, and that is the
    /// signal that these tests are free to be split up again.
    @Test func dependencySlotIsProcessWideNotPerInstance() async throws {
        let installed = FakePlaybackController()
        let installer = SkipBackwardIntent()
        installer.playback = installed

        // A different intent instance, never handed the fake.
        _ = try await SkipForwardIntent().perform()

        #expect(installed.skippedForward, "expected the shared slot to hand this fake to the other intent")
    }

    // MARK: - BookEntity mapping

    @Test func bookEntityCarriesIdTitleAuthorSeries() {
        let entity = BookEntity(
            id: "book-1",
            title: "Dungeon Crawler Carl",
            seriesTitle: "Dungeon Crawler Carl",
            author: "Matt Dinniman"
        )

        #expect(entity.id == "book-1")
        #expect(entity.title == "Dungeon Crawler Carl")
        #expect(entity.author == "Matt Dinniman")
        #expect(entity.seriesTitle == "Dungeon Crawler Carl")
    }

    /// `id` is the stable book id — the value Shortcuts persists and later
    /// re-hydrates through `entities(for:)`.
    @Test func bookEntityIdIsStableBookId() {
        let entity = BookEntity(id: "abc-123", title: "Title", author: "Author")
        #expect(entity.id == "abc-123")
    }

    // MARK: - Transport intent routing

    @Test func togglePlaybackIntentRoutesToTogglePlayPause() async throws {
        let fake = FakePlaybackController()
        let intent = TogglePlaybackIntent()
        intent.playback = fake

        _ = try await intent.perform()

        #expect(fake.toggled)
    }

    @Test func skipForwardIntentRoutesToSkipForward() async throws {
        let fake = FakePlaybackController()
        let intent = SkipForwardIntent()
        intent.playback = fake

        _ = try await intent.perform()

        #expect(fake.skippedForward)
    }

    @Test func skipBackwardIntentRoutesToSkipBackward() async throws {
        let fake = FakePlaybackController()
        let intent = SkipBackwardIntent()
        intent.playback = fake

        _ = try await intent.perform()

        #expect(fake.skippedBackward)
    }

    // MARK: - Book intent routing

    @Test func playBookIntentRoutesToPlayBook() async throws {
        let fake = FakePlaybackController()
        let intent = PlayBookIntent()
        intent.target = BookEntity(id: "book-42", title: "Mistborn", author: "Brandon Sanderson")
        intent.playback = fake

        _ = try await intent.perform()

        #expect(fake.playedBookId == "book-42")
    }

    @Test func resumeIntentPlaysTheMostRecentBook() async throws {
        let playback = FakePlaybackController()
        let intent = ResumePlaybackIntent()
        intent.playback = playback
        intent.lastPlayed = FakeLastPlayedBookProvider(bookId: "book-99")

        _ = try await intent.perform()

        #expect(playback.playedBookId == "book-99")
    }

    @Test func resumeIntentThrowsWhenNothingToResume() async throws {
        let playback = FakePlaybackController()
        let intent = ResumePlaybackIntent()
        intent.playback = playback
        intent.lastPlayed = FakeLastPlayedBookProvider(bookId: nil)

        await #expect(throws: ResumePlaybackError.nothingToResume) {
            _ = try await intent.perform()
        }

        #expect(playback.playedBookId == nil)
    }
}
