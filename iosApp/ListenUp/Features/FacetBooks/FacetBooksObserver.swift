import SwiftUI
import Shared

/// The render phase of the facet-browse screen, flattened from `BrowseFacetUiState`.
///
/// `Loading` is the only non-`ready` phase that shows the hero (it has the route-supplied
/// name); `NotFound` collapses to `.notFound` (the facet resolved to no live row).
enum FacetBooksPhase: Equatable {
    case loading
    case ready
    case notFound
}

/// A flat projection of `BrowseFacetUiState`, computed once so the mapping is pure and
/// unit-testable rather than buried in the observer's `apply`.
struct FacetBooksSnapshot: Equatable {
    var phase: FacetBooksPhase = .loading
    var facetName: String = ""
    var symbolName: String = "book"
    var hue: Color = .gray
    var books: [BookRow] = []
    var bookCount: Int = 0
    var totalDurationMs: Int64 = 0

    /// Flatten the sealed shared state into the flat snapshot the UI renders. The
    /// route-supplied `fallbackName` keeps the hero titled while `Loading`. The identity tile's
    /// hue/icon are derived from the resolved facet name via the same shared `FacetIdentity`
    /// derivation the genre destination page uses, so a tag/mood tile reads as the same visual
    /// family as a genre tile. `bookCount`/`totalDurationMs` are the server-aggregate stats from
    /// the shared VM (not `books.count`), mirroring `GenrePageSnapshot`.
    static func from(_ state: BrowseFacetUiState, fallbackName: String) -> FacetBooksSnapshot {
        switch onEnum(of: state) {
        case .loading:
            return FacetBooksSnapshot(phase: .loading, facetName: fallbackName)
        case .ready(let r):
            return FacetBooksSnapshot(
                phase: .ready,
                facetName: r.facetName,
                symbolName: sfSymbol(for: FacetIdentity.shared.icon(name: r.facetName)),
                hue: hueColor(FacetIdentity.shared.hue(name: r.facetName)),
                books: r.books.map { BookRow($0) },
                bookCount: Int(r.bookCount),
                totalDurationMs: r.totalDurationMs
            )
        case .notFound:
            return FacetBooksSnapshot(phase: .notFound, facetName: fallbackName)
        case .unknown:
            Log.error("Unexpected BrowseFacetUiState case")
            return FacetBooksSnapshot(phase: .notFound, facetName: fallbackName)
        }
    }
}

/// Observes `BrowseFacetViewModel`, flattening `BrowseFacetUiState` into flat `@Observable`
/// properties the SwiftUI screen binds to. The Kotlin `BookListItem`s are projected to native
/// `BookRow` value types at this boundary so the grid never feeds bridged objects to a `ForEach`.
@Observable
@MainActor
final class FacetBooksObserver {
    private(set) var phase: FacetBooksPhase = .loading
    private(set) var facetName: String = ""
    private(set) var symbolName: String = "book"
    private(set) var hue: Color = .gray
    private(set) var books: [BookRow] = []
    private(set) var bookCount: Int = 0
    private(set) var totalDurationMs: Int64 = 0

    /// The route-supplied name, shown in the hero while the Room observation hydrates.
    private let fallbackName: String
    private let viewModel: BrowseFacetViewModel
    private let bridge = FlowBridge()

    init(viewModel: BrowseFacetViewModel, fallbackName: String) {
        self.viewModel = viewModel
        self.fallbackName = fallbackName
        self.facetName = fallbackName
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    func load(kind: FacetKind, facetId: String) {
        viewModel.load(kind: kind, facetId: facetId)
    }

    private func apply(_ state: BrowseFacetUiState) {
        let snapshot = FacetBooksSnapshot.from(state, fallbackName: fallbackName)
        phase = snapshot.phase
        facetName = snapshot.facetName
        symbolName = snapshot.symbolName
        hue = snapshot.hue
        books = snapshot.books
        bookCount = snapshot.bookCount
        totalDurationMs = snapshot.totalDurationMs
    }
}
