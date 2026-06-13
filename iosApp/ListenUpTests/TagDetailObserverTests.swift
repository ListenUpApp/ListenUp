import Testing
@testable import ListenUp

/// Pure-seam coverage for the tag detail screen.
///
/// The sealed-state → `TagDetailSnapshot` flatten can't be exercised here: SKIE
/// bridges `TagDetailUiState` as a sealed *protocol* whose cases aren't
/// constructible from Swift, so behavioural verification of `from(_:)` lands at
/// the green-build pass (the app target proves the `onEnum` mapping compiles).
/// What *is* pure and constructible is the singular/plural book-count key, so that
/// seam is pinned here.
@Suite("TagDetailSnapshot")
struct TagDetailObserverTests {
    @Test func singleBookUsesSingularKey() {
        #expect(TagDetailSnapshot.bookCountKey(1) == "tag.book_count")
    }

    @Test func zeroBooksUsesPluralKey() {
        #expect(TagDetailSnapshot.bookCountKey(0) == "tag.books_count")
    }

    @Test func manyBooksUsePluralKey() {
        #expect(TagDetailSnapshot.bookCountKey(7) == "tag.books_count")
    }
}
