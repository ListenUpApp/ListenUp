import Testing
@testable import ListenUp

@Suite("SelectionFormatting")
struct SelectionFormattingTests {
    /// The count is what the shelf gained, so zero is a real outcome and needs a real sentence.
    @Test func addingNothingSaysSoRatherThanCountingZero() {
        #expect(SelectionFormatting.addedToShelf(count: 0) == String(localized: "selection.added_to_shelf_none"))
        #expect(
            SelectionFormatting.addedToCollection(count: 0)
                == String(localized: "selection.added_to_collection_none")
        )
    }

    @Test func oneBookGetsItsOwnSentence() {
        #expect(SelectionFormatting.addedToShelf(count: 1) == String(localized: "selection.added_to_shelf_one"))
        #expect(
            SelectionFormatting.addedToCollection(count: 1)
                == String(localized: "selection.added_to_collection_one")
        )
    }

    @Test func manyBooksAreCounted() {
        #expect(SelectionFormatting.addedToShelf(count: 5).contains("5"))
        #expect(SelectionFormatting.addedToCollection(count: 5).contains("5"))
    }

    /// A brand-new shelf is worth naming — it is what the user will look for next.
    @Test func aNewShelfIsNamedAndItsBooksCounted() {
        let message = SelectionFormatting.createdWithBooks(name: "Sci-Fi", count: 3)
        #expect(message.contains("Sci-Fi"))
        #expect(message.contains("3"))
    }

    @Test func aNewShelfWithOneBookDoesNotSayOneBooks() {
        let message = SelectionFormatting.createdWithBooks(name: "To Read", count: 1)
        #expect(message.contains("To Read"))
        #expect(!message.contains("1 books"))
    }
}
