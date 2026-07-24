import Testing
@testable import ListenUp

/// Pure-seam coverage for the shelf detail screen.
///
/// The sealed-state → `ShelfDetailSnapshot` flatten can't be exercised here: SKIE
/// bridges `ShelfDetailUiState` as a sealed *protocol* whose cases aren't
/// constructible from Swift, so behavioural verification of `from(_:)` lands at
/// the green-build pass (the app target proves the `onEnum` mapping compiles).
/// What *is* pure and constructible is the singular/plural book-count key, so that
/// seam is pinned here.
@Suite("ShelfDetailSnapshot")
struct ShelfDetailObserverTests {
    @Test func singleBookUsesSingularKey() {
        #expect(ShelfDetailSnapshot.bookCountKey(1) == "shelf.book_count")
    }

    @Test func zeroBooksUsesPluralKey() {
        #expect(ShelfDetailSnapshot.bookCountKey(0) == "shelf.books_count")
    }

    @Test func manyBooksUsePluralKey() {
        #expect(ShelfDetailSnapshot.bookCountKey(7) == "shelf.books_count")
    }
}
