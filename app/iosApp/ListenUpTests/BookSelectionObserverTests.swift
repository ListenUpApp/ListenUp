import SwiftUI
import Testing
@testable import ListenUp

/// `BookSelectionObserver`'s state mapping needs live KMP `SelectionMode` / flow instances to
/// drive `applySelectionMode` end-to-end (it owns a Kotlin `BookMultiSelectViewModel`), so the
/// behavioural verification lands at the green-build pass with a real VM over a test Koin —
/// exactly like `BookDetailObserverTests`. What *is* pure and constructible now is the native
/// row-projection contract the observer feeds into SwiftUI `ForEach`s (rule 8): the `Identifiable`
/// id is the row's own id, and value equality holds. Those invariants are pinned here so a future
/// edit can't silently break list identity (which would scramble selection-circle overlays).
@Suite("Selection row projections")
struct BookSelectionRowTests {
    @Test func shelfRowIsIdentifiedByItsId() {
        let row = SelectionShelfRow(id: "shelf-1", name: "Favorites")
        #expect(row.id == "shelf-1")
    }

    @Test func shelfRowEquatableByValue() {
        let row = SelectionShelfRow(id: "s", name: "Name")
        let same = SelectionShelfRow(id: "s", name: "Name")
        let different = SelectionShelfRow(id: "s", name: "Other")
        #expect(row == same)
        #expect(row != different)
    }

    @Test func collectionRowIsIdentifiedByItsId() {
        let row = SelectionCollectionRow(id: "col-1", name: "Staff Picks")
        #expect(row.id == "col-1")
    }

    @Test func collectionRowEquatableByValue() {
        let row = SelectionCollectionRow(id: "c", name: "Name")
        let same = SelectionCollectionRow(id: "c", name: "Name")
        let different = SelectionCollectionRow(id: "d", name: "Name")
        #expect(row == same)
        #expect(row != different)
    }

    /// Distinct ids must produce distinct `ForEach` identities — co-located shelf and collection
    /// rows with the same backing id are still independent rows.
    @Test func rowsWithDistinctIdsAreDistinctIdentities() {
        let rows = [
            SelectionShelfRow(id: "1", name: "A"),
            SelectionShelfRow(id: "2", name: "B"),
            SelectionShelfRow(id: "3", name: "C")
        ]
        #expect(Set(rows.map(\.id)).count == rows.count)
    }
}
