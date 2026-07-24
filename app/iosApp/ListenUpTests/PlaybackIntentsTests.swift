import Testing
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

/// Run serialized so parallel tests don't race on the shared AppDependencyManager
/// dependency slot. @MainActor lets us construct FakePlaybackController and read
/// its properties without await, and eliminates the actor-hop that made the
/// compiler warn "no async operations within await expression".
@MainActor
@Suite(.serialized)
struct PlaybackIntentsTests {

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
