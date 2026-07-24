import SwiftUI
import Shared

/// The render phase of the "See all" contributor-books screen, flattened from
/// `ContributorBooksUiState`.
enum ContributorBooksPhase: Equatable {
    case loading
    case ready
    case error
}

/// A native, value-typed projection of one `SeriesGroup` for SwiftUI lists — the Kotlin
/// `BookListItem`s are snapshotted to `BookRow` once, off the diff path (same re-bridging
/// hazard `BookRow`/`SeriesRow` exist to kill).
struct ContributorSeriesGroupRow: Identifiable, Equatable {
    let seriesName: String
    let books: [BookRow]

    var id: String { seriesName }

    init(seriesName: String, books: [BookRow]) {
        self.seriesName = seriesName
        self.books = books
    }

    init(_ group: SeriesGroup) {
        self.seriesName = group.seriesName
        self.books = group.books.map { BookRow($0) }
    }
}

/// A pure, unit-testable flattening of `ContributorBooksUiState` into the native values the
/// screen renders. Extracted from the observer's `apply` so the mapping is testable rather than
/// buried in `@Observable` state. The route-supplied names keep the header titled while loading.
struct ContributorBooksSnapshot: Equatable {
    var phase: ContributorBooksPhase = .loading
    var contributorName: String = ""
    var roleDisplayName: String = ""
    var seriesGroups: [ContributorSeriesGroupRow] = []
    var standaloneBooks: [BookRow] = []
    var bookProgress: [String: Float] = [:]
    var errorMessage: String?

    /// Total books across series groups and standalone books.
    var totalBooks: Int { seriesGroups.reduce(0) { $0 + $1.books.count } + standaloneBooks.count }

    static func from(
        _ state: ContributorBooksUiState,
        fallbackName: String,
        fallbackRole: String
    ) -> ContributorBooksSnapshot {
        switch onEnum(of: state) {
        case .idle, .loading:
            return ContributorBooksSnapshot(
                phase: .loading,
                contributorName: fallbackName,
                roleDisplayName: fallbackRole
            )
        case .ready(let r):
            return ContributorBooksSnapshot(
                phase: .ready,
                contributorName: r.contributorName,
                roleDisplayName: r.roleDisplayName,
                seriesGroups: r.seriesGroups.map { ContributorSeriesGroupRow($0) },
                standaloneBooks: r.standaloneBooks.map { BookRow($0) },
                bookProgress: mapProgress(r.bookProgress)
            )
        case .error(let err):
            return ContributorBooksSnapshot(
                phase: .error,
                contributorName: fallbackName,
                roleDisplayName: fallbackRole,
                errorMessage: err.message
            )
        case .unknown:
            Log.error("Unexpected ContributorBooksUiState case")
            return ContributorBooksSnapshot(
                phase: .error,
                contributorName: fallbackName,
                roleDisplayName: fallbackRole,
                errorMessage: String(localized: "common.something_went_wrong")
            )
        }
    }

    /// `Map<BookId, Float>` bridges as `[BookId: Float]`; normalize keys to the book-id string
    /// the grid looks up by (mirrors `LibraryObserver.mapProgress`).
    private static func mapProgress(_ raw: [BookId: Float]) -> [String: Float] {
        var result: [String: Float] = [:]
        for (key, value) in raw {
            result[key.value] = value
        }
        return result
    }
}

/// Observes `ContributorBooksViewModel`, flattening `ContributorBooksUiState` into flat
/// `@Observable` properties. Thin over `FlowBridge`; the Kotlin books are projected to native
/// `BookRow` value types at this boundary so the grid never feeds bridged objects to a `ForEach`.
@Observable
@MainActor
final class ContributorBooksObserver {
    private(set) var phase: ContributorBooksPhase = .loading
    private(set) var contributorName: String = ""
    private(set) var roleDisplayName: String = ""
    private(set) var seriesGroups: [ContributorSeriesGroupRow] = []
    private(set) var standaloneBooks: [BookRow] = []
    private(set) var bookProgress: [String: Float] = [:]
    private(set) var errorMessage: String?

    var totalBooks: Int { seriesGroups.reduce(0) { $0 + $1.books.count } + standaloneBooks.count }

    private let fallbackName: String
    private let fallbackRole: String
    private let viewModel: ContributorBooksViewModel
    private let bridge = FlowBridge()

    init(viewModel: ContributorBooksViewModel, fallbackName: String, fallbackRole: String) {
        self.viewModel = viewModel
        self.fallbackName = fallbackName
        self.fallbackRole = fallbackRole
        self.contributorName = fallbackName
        self.roleDisplayName = fallbackRole
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    func loadBooks(contributorId: String, role: String) {
        viewModel.loadBooks(contributorId: contributorId, role: role)
    }

    private func apply(_ state: ContributorBooksUiState) {
        let snapshot = ContributorBooksSnapshot.from(
            state,
            fallbackName: fallbackName,
            fallbackRole: fallbackRole
        )
        phase = snapshot.phase
        contributorName = snapshot.contributorName
        roleDisplayName = snapshot.roleDisplayName
        seriesGroups = snapshot.seriesGroups
        standaloneBooks = snapshot.standaloneBooks
        bookProgress = snapshot.bookProgress
        errorMessage = snapshot.errorMessage
    }
}
