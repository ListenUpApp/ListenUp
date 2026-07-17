import SwiftUI
import Shared

/// The render phase of the browse-by-genre screen, flattened from `BrowseGenreUiState`.
enum BrowseGenrePhase: Equatable {
    case loading
    case ready
    case error(String)
}

/// One node in the genre tree, flattened to a native value type at the observer boundary so the
/// SwiftUI `ForEach` never re-reads a bridged Kotlin `Genre`. `depth` drives the row's indentation
/// (derived from the materialized path); `bookCount` labels the row.
struct GenreNodeModel: Identifiable, Equatable {
    let id: String
    let name: String
    let depth: Int
    let bookCount: Int
}

/// Observes `BrowseGenreViewModel`, flattening `BrowseGenreUiState` into flat `@Observable`
/// properties the SwiftUI screen binds to.
///
/// The shared VM returns the selected genre's books as bare ids (`List<BookId>`); this observer
/// hydrates them to native `BookRow` value types via `BookRepository.getBookListItems` so the grid
/// shows real covers and never feeds bridged Kotlin objects to a `ForEach`.
@Observable
@MainActor
final class BrowseGenreObserver {
    private(set) var phase: BrowseGenrePhase = .loading
    private(set) var genres: [GenreNodeModel] = []
    private(set) var selectedGenreId: String?
    private(set) var includeDescendants = false
    private(set) var isFetchingBooks = false
    private(set) var books: [BookRow] = []

    private let viewModel: BrowseGenreViewModel
    private let bridge = FlowBridge()

    /// The book ids most recently hydrated, so an unchanged id set doesn't re-fetch on every emit.
    private var hydratedIds: [String] = []
    private var hydrationTask: Task<Void, Never>?

    init(viewModel: BrowseGenreViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func select(genreId: String) {
        viewModel.selectGenre(genreId: GenreId(value: genreId))
    }

    func toggleIncludeDescendants() {
        viewModel.toggleIncludeDescendants()
    }

    // MARK: - State mapping

    private func apply(_ state: BrowseGenreUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready
            genres = ready.genres.map { genre in
                GenreNodeModel(
                    id: genre.id,
                    name: genre.name,
                    depth: Self.depth(ofPath: genre.path),
                    bookCount: Int(genre.bookCount)
                )
            }
            selectedGenreId = ready.selectedGenreId?.value
            includeDescendants = ready.includeDescendants
            isFetchingBooks = ready.isFetchingBooks
            hydrateBooks(ready.books.map { $0.value })
        case .error(let err):
            phase = .error(err.message.message)
        case .unknown:
            Log.error("Unexpected BrowseGenreUiState case")
            phase = .error(String(localized: "common.something_went_wrong"))
        }
    }

    /// Depth = number of path separators inside the trimmed materialized path
    /// ("/fiction/fantasy/epic-fantasy" → 2).
    private static func depth(ofPath path: String) -> Int {
        path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).filter { $0 == "/" }.count
    }

    /// Resolve the selected genre's book ids to native `BookRow`s, preserving the returned order.
    /// Skips when the id set is unchanged so repeated emits don't re-query.
    private func hydrateBooks(_ ids: [String]) {
        guard ids != hydratedIds else { return }
        hydratedIds = ids
        hydrationTask?.cancel()

        if ids.isEmpty {
            books = []
            return
        }

        hydrationTask = Task { [weak self] in await self?.hydrate(ids: ids) }
    }

    /// Resolve `ids` to native `BookRow`s and publish them, preserving `ids` order. Reads the
    /// repository fresh from the shared container inside this async method (matching the codebase's
    /// suspend-await precedent) so no non-Sendable value is captured across the actor hop.
    private func hydrate(ids: [String]) async {
        let items = (try? await Dependencies.shared.bookRepository.getBookListItems(ids: ids)) ?? []
        guard !Task.isCancelled else { return }
        let byId = Dictionary(items.map { ($0.idString, $0) }, uniquingKeysWith: { first, _ in first })
        books = ids.compactMap { byId[$0] }.map { BookRow($0) }
    }
}
