import Testing
@testable import ListenUp

@Suite("Cover drag payload")
struct CoverInteractionTests {
    @Test func namesTitleAndAuthor() {
        #expect(BookDragItem(title: "Dune", author: "Frank Herbert").plainText == "Dune — Frank Herbert")
    }

    @Test func fallsBackToTheTitleWithoutAnAuthor() {
        #expect(BookDragItem(title: "Dune", author: "  ").plainText == "Dune")
    }
}
