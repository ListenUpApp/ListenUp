import SwiftUI
import Shared

/// Observes `LibraryViewModel` — flattens the sealed `LibraryUiState` into flat
/// `@Observable` properties for all four library tabs. Thin over `FlowBridge`.
@Observable
@MainActor
final class LibraryObserver {
    // MARK: - Flattened state

    private(set) var books: [BookListItem] = []
    private(set) var bookProgress: [String: Float] = [:]
    private(set) var booksSortState: SortState?
    private(set) var series: [SeriesWithBooks_] = []
    private(set) var seriesSortState: SortState?
    private(set) var authors: [ContributorWithBookCount_] = []
    private(set) var authorsSortState: SortState?
    private(set) var narrators: [ContributorWithBookCount_] = []
    private(set) var narratorsSortState: SortState?
    private(set) var isLoading: Bool = true
    private(set) var isEmpty: Bool = false
    private(set) var isSyncing: Bool = false
    private(set) var errorMessage: String?

    private let viewModel: LibraryViewModel
    private let bridge = FlowBridge()

    init(viewModel: LibraryViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
    }

    func stopObserving() {
        bridge.cancelAll()
    }

    // MARK: - Lifecycle & actions

    func onScreenVisible() {
        viewModel.onScreenVisible()
    }

    func refresh() {
        viewModel.onEvent(event: LibraryUiEventRefreshRequested.shared)
    }

    func setBooksSortCategory(_ category: SortCategory) {
        viewModel.onEvent(event: LibraryUiEventBooksCategoryChanged(category: category))
    }

    func toggleBooksSortDirection() {
        viewModel.onEvent(event: LibraryUiEventBooksDirectionToggled.shared)
    }

    func setSeriesSortCategory(_ category: SortCategory) {
        viewModel.onEvent(event: LibraryUiEventSeriesCategoryChanged(category: category))
    }

    func toggleSeriesSortDirection() {
        viewModel.onEvent(event: LibraryUiEventSeriesDirectionToggled.shared)
    }

    func setAuthorsSortCategory(_ category: SortCategory) {
        viewModel.onEvent(event: LibraryUiEventAuthorsCategoryChanged(category: category))
    }

    func toggleAuthorsSortDirection() {
        viewModel.onEvent(event: LibraryUiEventAuthorsDirectionToggled.shared)
    }

    func setNarratorsSortCategory(_ category: SortCategory) {
        viewModel.onEvent(event: LibraryUiEventNarratorsCategoryChanged(category: category))
    }

    func toggleNarratorsSortDirection() {
        viewModel.onEvent(event: LibraryUiEventNarratorsDirectionToggled.shared)
    }

    // MARK: - State mapping

    private func apply(_ state: LibraryUiState) {
        switch onEnum(of: state) {
        case .loading:
            isLoading = true
            errorMessage = nil
        case .loaded(let l):
            isLoading = false
            errorMessage = nil
            books = Array(l.books)
            bookProgress = mapProgress(l.bookProgress)
            booksSortState = l.booksSortState
            series = Array(l.series)
            seriesSortState = l.seriesSortState
            authors = Array(l.authors)
            authorsSortState = l.authorsSortState
            narrators = Array(l.narrators)
            narratorsSortState = l.narratorsSortState
            isEmpty = l.isEmpty
            isSyncing = l.isSyncing
        case .error(let e):
            isLoading = false
            errorMessage = e.message
        }
    }

    /// `Map<BookId, Float>` arrives as `[AnyHashable: KotlinFloat]` over the SKIE
    /// boundary — the `BookId` value-class key bridges as `AnyHashable`. Keys are
    /// normalized to the book-id string the UI looks up by.
    private func mapProgress(_ raw: [AnyHashable: KotlinFloat]) -> [String: Float] {
        var result: [String: Float] = [:]
        for (key, value) in raw {
            result[String(describing: key.base)] = value.floatValue
        }
        return result
    }
}
