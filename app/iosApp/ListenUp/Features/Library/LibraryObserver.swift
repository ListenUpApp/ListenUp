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
    /// `authors` grouped for the list — regrouped only when the rows or the sort change.
    private(set) var authorSections: [ContributorLetterGrouping.Group] = []
    private(set) var authorsSortState: SortState?
    private(set) var narrators: [ContributorRow] = []
    /// `narrators` grouped for the list — regrouped only when the rows or the sort change.
    private(set) var narratorSections: [ContributorLetterGrouping.Group] = []
    private(set) var narratorsSortState: SortState?
    /// When true, leading articles (A, An, The) are ignored when sorting/grouping by Title/Name —
    /// drives the "Title sort" toggle and the article-aware section letters. Shared, persisted state.
    private(set) var ignoreTitleArticles: Bool = true
    private(set) var isLoading: Bool = true
    private(set) var isEmpty: Bool = false
    private(set) var isSyncing: Bool = false
    private(set) var errorMessage: String?

    @ObservationIgnored private var authorSectionCache = ContributorSectionCache()
    @ObservationIgnored private var narratorSectionCache = ContributorSectionCache()

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
        switch state.sealedType() {
        case .loading:
            isLoading = true
            errorMessage = nil
        case .loaded(let lType):
            let l = lType.value
            isLoading = false
            errorMessage = nil
            books = l.books.map { BookRow($0) }
            bookProgress = mapProgress(l.bookProgress)
            booksSortState = l.booksSortState
            series = l.series.map { SeriesRow($0) }
            seriesProgress = mapSeriesProgress(l.seriesProgress)
            seriesSortState = l.seriesSortState
            authorsSortState = l.authorsSortState
            narratorsSortState = l.narratorsSortState
            applyContributors(
                l.authors.map { ContributorRow($0) },
                isNameSort: l.authorsSortState.category == .name,
                cache: &authorSectionCache,
                rows: \.authors,
                sections: \.authorSections
            )
            applyContributors(
                l.narrators.map { ContributorRow($0) },
                isNameSort: l.narratorsSortState.category == .name,
                cache: &narratorSectionCache,
                rows: \.narrators,
                sections: \.narratorSections
            )
            ignoreTitleArticles = l.ignoreTitleArticles
            isEmpty = l.isEmpty
            isSyncing = l.isSyncing
        case .error(let eType):
            let e = eType.value
            isLoading = false
            errorMessage = e.message
        }
    }

    /// Publishes a contributor list only when it changed. An unchanged re-emit (a position save, a
    /// sync tick) writes nothing, so the Authors and Narrators lists aren't invalidated by it.
    private func applyContributors(
        _ newRows: [ContributorRow],
        isNameSort: Bool,
        cache: inout ContributorSectionCache,
        rows: ReferenceWritableKeyPath<LibraryObserver, [ContributorRow]>,
        sections: ReferenceWritableKeyPath<LibraryObserver, [ContributorLetterGrouping.Group]>
    ) {
        guard cache.update(rows: newRows, isNameSort: isNameSort) else { return }
        self[keyPath: rows] = cache.rows
        self[keyPath: sections] = cache.sections
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
