import Testing
@testable import ListenUp
@preconcurrency import Shared

/// The car's Continue Listening list is every book in progress — the loaded one included — exactly as Android Auto
/// reads it (`BrowseTreeProvider` → `HomeRepository`), not Home's rail, which leaves the loaded book out (spec F1).
@Suite("CarPlay home wrapper")
@MainActor
struct CarPlayHomeWrapperTests {
    @Test func itemsMapEveryBookInProgressInOrder() {
        let book = ContinueListeningBook(
            bookId: "a", title: "A", authorNames: "W", narratorNames: nil, coverPath: nil, coverHash: nil,
            progress: 0.5, currentPositionMs: 1, totalDurationMs: 2, lastPlayedAtMs: 0, isDownloaded: false
        )
        let items = CarPlayHomeWrapper.items(from: [
            ContinueListeningItemReady(bookId: "a", book: book),
            ContinueListeningItemLoading(bookId: "b")
        ])
        #expect(items.map(\.id) == ["a", "b"])
        #expect(items[1].isLoading)
    }
}
