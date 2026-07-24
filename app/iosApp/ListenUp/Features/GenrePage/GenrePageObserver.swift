import SwiftUI
import Shared

/// Observes `GenreDestinationViewModel`, flattening `GenreDestinationUiState` into flat
/// `@Observable` properties the SwiftUI screen binds to. The Kotlin `BookListItem`s, sub-genres,
/// and breadcrumb are projected to native value types at this boundary — mirrors
/// `FacetBooksObserver`.
@Observable
@MainActor
final class GenrePageObserver {
    private(set) var phase: GenrePagePhase = .loading
    private(set) var name: String = ""
    private(set) var slug: String = ""
    private(set) var blurb: String?
    private(set) var symbolName: String = "book"
    private(set) var hue: Color = .gray
    private(set) var breadcrumb: [GenreCrumbRow] = []
    private(set) var subGenres: [SubGenreRow] = []
    private(set) var hasSubs: Bool = false
    private(set) var includeSubGenres: Bool = false
    private(set) var bookCount: Int = 0
    private(set) var totalDurationMs: Int64 = 0
    private(set) var books: [BookRow] = []

    /// The route-supplied name, shown in the header while Room hydrates the authoritative identity.
    private let fallbackName: String
    private let viewModel: GenreDestinationViewModel
    private let bridge = FlowBridge()

    init(viewModel: GenreDestinationViewModel, fallbackName: String) {
        self.viewModel = viewModel
        self.fallbackName = fallbackName
        self.name = fallbackName
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func load(genreId: String) {
        viewModel.load(genreId: GenreId(value: genreId))
    }

    func toggleIncludeSubGenres() {
        viewModel.toggleIncludeSubGenres()
    }

    // MARK: - State mapping

    private func apply(_ state: GenreDestinationUiState) {
        let snapshot = GenrePageSnapshot.from(state, fallbackName: fallbackName)
        phase = snapshot.phase
        name = snapshot.name
        slug = snapshot.slug
        blurb = snapshot.blurb
        symbolName = snapshot.symbolName
        hue = snapshot.hue
        breadcrumb = snapshot.breadcrumb
        subGenres = snapshot.subGenres
        hasSubs = snapshot.hasSubs
        includeSubGenres = snapshot.includeSubGenres
        bookCount = snapshot.bookCount
        totalDurationMs = snapshot.totalDurationMs
        books = snapshot.books
    }
}
