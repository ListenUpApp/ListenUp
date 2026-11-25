import ListenupContract
import Shared
import Testing
@testable import ListenUp

/// Coverage for the shelf detail screen: the singular/plural book-count key, and the sealed-state →
/// `ShelfDetailSnapshot` → observer flatten.
///
/// The reorder arithmetic `isOwner` gates is not iOS's own — `reorderedIds` is the shared function,
/// pinned in `ShelfOrderTest`.
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

    // MARK: - State mapping

    /// `detail.isOwner` is deliberately the opposite of the state's own `isOwner`: the snapshot must
    /// read the state's.
    private func ready(isOwner: Bool) -> ShelfDetailUiStateReady {
        ShelfDetailUiStateReady(
            detail: ShelfDetail(
                id: ShelfId(value: "shelf-1"),
                name: "Rainy Days",
                description: "For the sofa",
                isPrivate: true,
                isOwner: !isOwner,
                bookCount: 2,
                totalDurationSeconds: 7_200,
                books: [
                    ShelfBook(
                        id: BookId(value: "book-1"),
                        title: "Piranesi",
                        authorNames: ["Susanna Clarke"],
                        coverPath: "/covers/piranesi.jpg",
                        coverHash: "hash-1"
                    ),
                    ShelfBook(
                        id: BookId(value: "book-2"),
                        title: "Good Omens",
                        authorNames: ["Terry Pratchett", "Neil Gaiman"],
                        coverPath: nil,
                        coverHash: nil
                    )
                ]
            ),
            isOwner: isOwner
        )
    }

    @Test func idleAndLoadingBothFlattenToAnEmptyLoadingSnapshot() {
        #expect(ShelfDetailSnapshot.from(ShelfDetailUiStateIdle.shared) == ShelfDetailSnapshot())
        #expect(ShelfDetailSnapshot.from(ShelfDetailUiStateLoading.shared) == ShelfDetailSnapshot())
        #expect(ShelfDetailSnapshot().phase == .loading)
    }

    @Test func readyFlattensTheShelfAndItsBooks() {
        let snapshot = ShelfDetailSnapshot.from(ready(isOwner: true))

        #expect(snapshot.phase == .ready)
        #expect(snapshot.shelfName == "Rainy Days")
        #expect(snapshot.shelfDescription == "For the sofa")
        #expect(snapshot.isOwner)
        #expect(snapshot.bookCount == 2)
        #expect(snapshot.books.map(\.id) == ["book-1", "book-2"])
        #expect(snapshot.books.map(\.title) == ["Piranesi", "Good Omens"])
        #expect(snapshot.books.map(\.authorNames) == [["Susanna Clarke"], ["Terry Pratchett", "Neil Gaiman"]])
        #expect(snapshot.books.map(\.coverPath) == ["/covers/piranesi.jpg", nil])
        #expect(snapshot.books.map(\.coverHash) == ["hash-1", nil])
    }

    @Test func readyReadsOwnershipFromTheStateNotTheShelf() {
        #expect(ShelfDetailSnapshot.from(ready(isOwner: false)).isOwner == false)
    }

    @Test func errorCarriesItsMessageAndNothingElse() {
        let snapshot = ShelfDetailSnapshot.from(ShelfDetailUiStateError(message: "Shelf not found."))
        #expect(snapshot == ShelfDetailSnapshot(phase: .error("Shelf not found.")))
    }

    @Test func aSecondStateReplacesEveryObserverField() {
        let observer = ShelfDetailObserver(viewModel: Dependencies.shared.createShelfDetailViewModel())

        observer.apply(ready(isOwner: true))
        #expect(observer.phase == .ready)
        #expect(observer.shelfName == "Rainy Days")
        #expect(observer.shelfDescription == "For the sofa")
        #expect(observer.books.map(\.id) == ["book-1", "book-2"])
        #expect(observer.isOwner)

        observer.apply(ShelfDetailUiStateError(message: "Shelf not found."))
        #expect(observer.phase == .error("Shelf not found."))
        #expect(observer.shelfName.isEmpty)
        #expect(observer.shelfDescription == nil)
        #expect(observer.books.isEmpty)
        #expect(observer.isOwner == false)
    }

    // MARK: - A refused reorder

    @Test func aRefusedReorderPutsTheGridBackAndSaysWhy() {
        let alerts = ErrorAlertCenter()
        let observer = ShelfDetailObserver(viewModel: Dependencies.shared.createShelfDetailViewModel(), alerts: alerts)
        observer.apply(ready(isOwner: true))

        observer.reorder(draggedId: "book-2", onto: "book-1")
        #expect(observer.books.map(\.id) == ["book-2", "book-1"])

        observer.showMutationFailure("Couldn't save the new order.")
        #expect(observer.books.map(\.id) == ["book-1", "book-2"])
        #expect(alerts.current?.message == "Couldn't save the new order.")
    }

    @Test func aRefusedRemovalLeavesTheGridAsTheServerLastSentIt() {
        let alerts = ErrorAlertCenter()
        let observer = ShelfDetailObserver(viewModel: Dependencies.shared.createShelfDetailViewModel(), alerts: alerts)
        observer.apply(ready(isOwner: true))

        observer.showMutationFailure("Couldn't remove the book.")
        #expect(observer.books.map(\.id) == ["book-1", "book-2"])
        #expect(alerts.current?.message == "Couldn't remove the book.")
    }
}
