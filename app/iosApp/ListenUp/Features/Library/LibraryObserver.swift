import SwiftUI
import Shared

/// Observes `LibraryViewModel` — flattens the sealed `LibraryUiState` into flat
/// `@Observable` properties for all four library tabs. Thin over `FlowBridge`.
@Observable
@MainActor
final class LibraryObserver {
    // MARK: - Flattened state

    private(set) var books: [BookRow] = []
    private(set) var bookProgress: [String: Float] = [:]
    private(set) var booksSortState: SortState?
    private(set) var series: [SeriesRow] = []
    private(set) var seriesProgress: [String: SeriesProgressState] = [:]
    private(set) var seriesSortState: SortState?
    private(set) var authors: [ContributorRow] = []
    private(set) var authorsSortState: SortState?
    private(set) var narrators: [ContributorRow] = []
    private(set) var narratorsSortState: SortState?
    /// When true, leading articles (A, An, The) are ignored when sorting/grouping by Title/Name —
    /// drives the "Title sort" toggle and the article-aware section letters. Shared, persisted state.
    private(set) var ignoreTitleArticles: Bool = true
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

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

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

    /// Flip whether leading articles are ignored when sorting Title (Books) / Name (Series). The
    /// shared ViewModel re-sorts and persists; `apply` updates `ignoreTitleArticles` and the grids
    /// re-section.
    func toggleIgnoreTitleArticles() {
        viewModel.onEvent(event: LibraryUiEventToggleIgnoreTitleArticles.shared)
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
            books = l.books.map { BookRow($0) }
            bookProgress = mapProgress(l.bookProgress)
            booksSortState = l.booksSortState
            series = l.series.map { SeriesRow($0) }
            seriesProgress = mapSeriesProgress(l.seriesProgress)
            seriesSortState = l.seriesSortState
            authors = l.authors.map { ContributorRow($0) }
            authorsSortState = l.authorsSortState
            narrators = l.narrators.map { ContributorRow($0) }
            narratorsSortState = l.narratorsSortState
            ignoreTitleArticles = l.ignoreTitleArticles
            isEmpty = l.isEmpty
            isSyncing = l.isSyncing
        case .error(let e):
            isLoading = false
            errorMessage = e.message
        case .unknown:
            Log.error("Unexpected LibraryUiState case")
            isLoading = false
            errorMessage = String(localized: "common.something_went_wrong")
        }
    }

    /// `Map<BookId, Float>` arrives as `[BookId: Float]` over the Swift Export
    /// boundary — the `BookId` value-class key bridges as its wrapper type. Keys are
    /// normalized to the book-id string the UI looks up by.
    private func mapProgress(_ raw: [BookId: Float]) -> [String: Float] {
        var result: [String: Float] = [:]
        for (key, value) in raw {
            result[key.value] = value
        }
        return result
    }

    /// Bridge `Map<SeriesId, SeriesProgress>` → `[String: SeriesProgressState]`.
    /// The `SeriesId` value-class key bridges as its wrapper type, matching `mapProgress`.
    private func mapSeriesProgress(_ raw: [SeriesId: SeriesProgress]) -> [String: SeriesProgressState] {
        var result: [String: SeriesProgressState] = [:]
        for (key, value) in raw {
            result[key.value] = SeriesProgressState(
                finishedCount: Int(value.finishedCount),
                totalCount: Int(value.totalCount)
            )
        }
        return result
    }
}
