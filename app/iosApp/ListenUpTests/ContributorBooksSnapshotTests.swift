import Testing
@testable import ListenUp

/// Pure-seam coverage for the contributor "See all" screen.
///
/// The shared `ContributorBooksUiState` isn't constructible from Swift, so the observer's
/// `apply(_:)` field mapping lands at the green-build pass. The native value projections and the
/// `totalBooks` roll-up are pure and constructible, so those are pinned here.
@Suite("ContributorBooksSnapshot")
struct ContributorBooksSnapshotTests {
    private func book(_ id: String) -> BookRow {
        BookRow(id: id, title: id, authorNames: "Author", hasDocuments: false, coverPath: nil)
    }

    @Test func defaultSnapshotIsLoading() {
        let snapshot = ContributorBooksSnapshot()
        #expect(snapshot.phase == .loading)
        #expect(snapshot.totalBooks == 0)
    }

    @Test func totalBooksSumsSeriesAndStandalone() {
        let snapshot = ContributorBooksSnapshot(
            phase: .ready,
            seriesGroups: [
                ContributorSeriesGroupRow(seriesName: "Stormlight", books: [book("a"), book("b")]),
                ContributorSeriesGroupRow(seriesName: "Mistborn", books: [book("c")])
            ],
            standaloneBooks: [book("d"), book("e")]
        )
        #expect(snapshot.totalBooks == 5)
    }
}
