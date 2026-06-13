import AppIntents
import Testing
@testable import ListenUp
@testable import ListenUpActivityKit

/// Pure-mapping and routing tests for the App Intents surface. The entity-query
/// I/O needs the live repository, so these exercise the testable seams: the
/// `BookEntity` projection/display and the `PlayBookIntent` → `playBook` route.
@MainActor
@Suite(.serialized)
struct AppIntentsTests {

    // MARK: - BookEntity mapping

    @Test func bookEntityCarriesIdTitleAuthor() {
        let entity = BookEntity(id: "book-1", title: "Dungeon Crawler Carl", author: "Matt Dinniman")

        #expect(entity.id == "book-1")
        #expect(entity.title == "Dungeon Crawler Carl")
        #expect(entity.author == "Matt Dinniman")
    }

    /// `Identifiable.id` is the stable book id — the value Shortcuts persists and
    /// later re-hydrates through `entities(for:)`.
    @Test func bookEntityIdIsStableBookId() {
        let entity = BookEntity(id: "abc-123", title: "Title", author: "Author")
        #expect(entity.id == "abc-123")
    }

    // MARK: - PlayBookIntent routing

    @Test func playBookIntentRoutesToPlayBook() async throws {
        let fake = FakePlaybackController()
        let intent = PlayBookIntent()
        intent.book = BookEntity(id: "book-42", title: "Mistborn", author: "Brandon Sanderson")
        intent.playback = fake

        _ = try await intent.perform()

        #expect(fake.playedBookId == "book-42")
    }
}
