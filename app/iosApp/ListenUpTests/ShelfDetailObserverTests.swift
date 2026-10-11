import Testing
@testable import ListenUp

/// Pure-seam coverage for the shelf detail screen.
///
/// The sealed-state → `ShelfDetailSnapshot` flatten isn't exercised here:
/// verification of `from(_:)` lands at the green-build pass (the app target proves
/// the `sealedType()` mapping compiles). What is pinned here is the singular/plural
/// book-count key, which is pure.
///
/// The same limit applies to `isOwner`: the branch that reads it lives inside `from(_:)`, so it is
/// covered by the green-build pass rather than by an assertion. The reorder arithmetic it gates is
/// not iOS's own — `reorderedIds` is the shared function, pinned in `ShelfOrderTest`.
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
