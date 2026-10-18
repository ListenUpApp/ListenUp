import Foundation
import ListenupContract
import Shared
import Testing
@testable import ListenUp

/// The My Shelves list's mapping from the shared `MyShelvesUiState` (spec §3.2 H24).
@MainActor
@Suite("My Shelves observer")
struct MyShelvesObserverTests {
    private func shelf(_ id: String, _ name: String) -> Shelf {
        Shelf(
            id: ShelfId(value: id),
            name: name,
            description: nil,
            isPrivate: false,
            ownerId: "u1",
            ownerDisplayName: "Simon",
            bookCount: 3,
            totalDurationSeconds: 3_600,
            createdAtMs: 0,
            updatedAtMs: 0,
            covers: []
        )
    }

    @Test func readyMapsShelvesToItems() {
        let phase = MyShelvesPhase(from: MyShelvesUiStateReady(shelves: [shelf("s1", "Winter"), shelf("s2", "Mood")]))
        guard case .ready(let items) = phase else {
            Issue.record("expected ready")
            return
        }
        #expect(items.map(\.name) == ["Winter", "Mood"])
        #expect(items.map(\.id) == ["s1", "s2"])
    }

    @Test func noShelvesIsReadyAndEmpty() {
        #expect(MyShelvesPhase(from: MyShelvesUiStateReady(shelves: [])) == .ready([]))
    }

    @Test func loadingAndErrorMap() {
        #expect(MyShelvesPhase(from: MyShelvesUiStateLoading.shared) == .loading)
        let error = MyShelvesPhase(from: MyShelvesUiStateError(error: UnexpectedClientError(correlationId: nil, debugInfo: nil)))
        #expect(error == .error)
    }
}
