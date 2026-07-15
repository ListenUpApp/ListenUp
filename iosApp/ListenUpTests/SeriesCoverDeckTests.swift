import Testing
@testable import ListenUp

/// Series imagery on iOS is a `CoverStack` of member-book covers — there is no dedicated series
/// cover. This pins that a member book's cover change flows into the deck's diff value (`CoverArt`),
/// so the series UI re-renders and `BookCoverImage` busts the cover cache (its `TaskKey` already
/// includes `coverPath`). Guards against a future refactor that drops `coverPath` from `CoverArt`.
@Suite("Series cover deck")
struct SeriesCoverDeckTests {
    @Test func coverArtCapturesMemberBookCoverPath() {
        let book = BookRow(
            id: "book-1", title: "The Way of Kings", authorNames: "Brandon Sanderson",
            hasDocuments: false, coverPath: "/covers/book-1.jpg"
        )
        #expect(CoverArt(book: book).coverPath == "/covers/book-1.jpg")
    }

    @Test func memberCoverChangeProducesDifferentCoverArt() {
        let before = BookRow(
            id: "book-1", title: "T", authorNames: "A",
            hasDocuments: false, coverPath: "/covers/book-1.jpg"
        )
        let after = BookRow(
            id: "book-1", title: "T", authorNames: "A",
            hasDocuments: false, coverPath: "/covers/book-1-v2.jpg"
        )
        #expect(CoverArt(book: before) != CoverArt(book: after))
    }
}
