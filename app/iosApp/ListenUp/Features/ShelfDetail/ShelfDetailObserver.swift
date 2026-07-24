import SwiftUI
import Shared

/// The render phase of the shelf detail screen, flattened from `ShelfDetailUiState`.
///
/// `Idle` and `Loading` from the shared VM both collapse to `.loading` here — the
/// screen has nothing to show until the shelf resolves, and the distinction isn't
/// user-visible.
enum ShelfDetailPhase: Equatable {
    case loading
    case ready
    case error(String)
}

/// A native, value-typed projection of `ShelfBook` for the shelf grid.
///
/// `ShelfBook` is a Swift-Export-bridged Kotlin object. Feeding it straight into the
/// `LazyVGrid` means every SwiftUI diff/layout/scroll pass re-reads its properties across
/// the Kotlin boundary (re-bridging UTF-16 strings) — the bug class that froze the Library
/// grid for ~28s. Snapshot the fields the grid needs into plain Swift values once, here.
/// See `BookRow.swift` for the canonical version of this pattern.
struct ShelfBookRow: Identifiable, Equatable {
    let id: String
    let title: String
    let authorNames: [String]
    let coverPath: String?
    let coverHash: String?

    init(_ book: ShelfBook) {
        self.id = book.idString
        self.title = book.title
        self.authorNames = Array(book.authorNames)   // copy the bridged list into a Swift array
        self.coverPath = book.coverPath
        self.coverHash = book.coverHash
    }
}

/// A flat projection of `ShelfDetailUiState`, computed once so the mapping is pure
/// and unit-testable rather than buried in the observer's `apply`.
struct ShelfDetailSnapshot: Equatable {
    var phase: ShelfDetailPhase = .loading
    var shelfName: String = ""
    var shelfDescription: String?
    var books: [ShelfBookRow] = []

    var bookCount: Int { books.count }

    /// The singular/plural "N books" subtitle key for a given count. Pure so the
    /// branch is unit-tested rather than buried in the view.
    static func bookCountKey(_ count: Int) -> String {
        count == 1 ? "shelf.book_count" : "shelf.books_count"
    }

    /// Flatten the sealed shared state into the flat snapshot the UI renders.
    static func from(_ state: ShelfDetailUiState) -> ShelfDetailSnapshot {
        switch onEnum(of: state) {
        case .idle, .loading:
            return ShelfDetailSnapshot(phase: .loading)
        case .ready(let r):
            return ShelfDetailSnapshot(
                phase: .ready,
                shelfName: r.detail.name,
                shelfDescription: r.detail.description_,
                books: r.detail.books.map { ShelfBookRow($0) }
            )
        case .error(let errorState):
            return ShelfDetailSnapshot(phase: .error(errorState.message))
        case .unknown:
            Log.error("Unexpected ShelfDetailUiState case")
            return ShelfDetailSnapshot(phase: .error(String(localized: "common.something_went_wrong")))
        }
    }
}

/// Observes `ShelfDetailViewModel`, flattening `ShelfDetailUiState` into flat
/// `@Observable` properties the SwiftUI screen binds to.
@Observable
@MainActor
final class ShelfDetailObserver {
    private(set) var phase: ShelfDetailPhase = .loading
    private(set) var shelfName: String = ""
    private(set) var shelfDescription: String?
    private(set) var books: [ShelfBookRow] = []

    var bookCount: Int { books.count }

    private let viewModel: ShelfDetailViewModel
    private let bridge = FlowBridge()

    init(viewModel: ShelfDetailViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    func loadShelf(_ id: String) { viewModel.loadShelf(shelfId: id) }

    private func apply(_ state: ShelfDetailUiState) {
        let snapshot = ShelfDetailSnapshot.from(state)
        phase = snapshot.phase
        shelfName = snapshot.shelfName
        shelfDescription = snapshot.shelfDescription
        books = snapshot.books
    }
}
