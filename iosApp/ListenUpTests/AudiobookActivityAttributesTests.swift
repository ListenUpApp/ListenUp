import Testing
import Foundation
@testable import ListenUpActivityKit

struct AudiobookActivityAttributesTests {

    @Test func contentStateSurvivesACodableRoundTrip() throws {
        let state = AudiobookActivityAttributes.ContentState(
            chapterTitle: "Chapter 7",
            isPlaying: true,
            bookProgress: 0.42,
            chapterProgress: 0.6,
            elapsedDescription: "2h 14m",
            remainingDescription: "4h 32m left"
        )

        let data = try JSONEncoder().encode(state)
        let decoded = try JSONDecoder().decode(AudiobookActivityAttributes.ContentState.self, from: data)

        #expect(decoded == state)
    }

    @Test func attributesCarryTheStaticBookIdentity() {
        let attributes = AudiobookActivityAttributes(
            bookId: "book-1",
            bookTitle: "The Way of Kings",
            authorName: "Brandon Sanderson"
        )

        #expect(attributes.bookId == "book-1")
        #expect(attributes.bookTitle == "The Way of Kings")
    }
}
