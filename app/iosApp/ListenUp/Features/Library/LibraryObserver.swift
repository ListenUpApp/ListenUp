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
    /// The Series scrubber's letters, rebuilt only when the series content changes.
    private(set) var seriesLetterIndex: [(letter: String, firstId: String)] = []
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
    /// The library itself has no books. A status filter that matches nothing is `isFilteredEmpty`.
    private(set) var isEmpty: Bool = false
    /// The Books view's reading-state filter. Session-scoped: the shared ViewModel never persists it.
    private(set) var statusFilter: BookStatusFilter = .all
    /// Whole-library counts per reading state, whatever the filter.
    private(set) var statusCounts: LibraryStatusCounts = .zero
    /// What each book's card says. Moves on a position tick without a content revision, so it is
    /// mapped outside the revision gate.
    private(set) var bookStatus: [String: LibraryCardState] = [:]
    /// The whole library's length in ms.
    private(set) var totalDurationMs: Int64 = 0
    /// The library has books, but the status filter matches none of them.
    private(set) var isFilteredEmpty = false
    private(set) var isSyncing: Bool = false
    private(set) var errorMessage: String?

    @ObservationIgnored private var contentRevision = ContentRevisionGate()
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

    func setStatusFilter(_ filter: BookStatusFilter) {
        viewModel.onEvent(event: LibraryUiEventStatusFilterChanged(filter: filter))
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

    /// The shared ViewModel re-emits the whole Library state on every position save (about every
    /// 5 seconds of playback) and every sync tick. Re-mapping every book and series across Swift
    /// Export on each of those froze the main thread on large libraries (2026-09-29 iOS audit,
    /// performance), so the content lists are re-mapped only when `contentRevision` moves; a
    /// progress or sync emission touches only the small slices that changed.
    private func apply(_ state: LibraryUiState) {
        switch state.sealedType() {
        case .loading:
            isLoading = true
            errorMessage = nil
        case .loaded(let lType):
            let loaded = lType.value
            isLoading = false
            errorMessage = nil
            if contentRevision.advance(to: loaded.contentRevision) {
                applyContent(loaded)
            }
            assignIfChanged(\.bookProgress, mapProgress(loaded.bookProgress))
            assignIfChanged(\.bookStatus, mapStatus(loaded.bookStatus))
            assignIfChanged(\.seriesProgress, mapSeriesProgress(loaded.seriesProgress))
            assignIfChanged(\.isSyncing, loaded.isSyncing)
        case .error(let eType):
            isLoading = false
            errorMessage = eType.value.message
        }
    }

    /// Everything the content revision covers: the four sorted lists and the intent that ordered them.
    private func applyContent(_ loaded: LibraryUiStateLoaded) {
        books = loaded.books.map { BookRow($0) }
        booksSortState = loaded.booksSortState
        series = loaded.series.map { SeriesRow($0) }
        seriesSortState = loaded.seriesSortState
        seriesLetterIndex = loaded.seriesSortState.category == .name
            ? seriesAlphabetIndex(from: series, ignoreArticles: loaded.ignoreTitleArticles)
            : []
        authorsSortState = loaded.authorsSortState
        narratorsSortState = loaded.narratorsSortState
        applyContributors(
            loaded.authors.map { ContributorRow($0) },
            isNameSort: loaded.authorsSortState.category == .name,
            cache: &authorSectionCache,
            rows: \.authors,
            sections: \.authorSections
        )
        applyContributors(
            loaded.narrators.map { ContributorRow($0) },
            isNameSort: loaded.narratorsSortState.category == .name,
            cache: &narratorSectionCache,
            rows: \.narrators,
            sections: \.narratorSections
        )
        ignoreTitleArticles = loaded.ignoreTitleArticles
        isEmpty = loaded.isEmpty
        // The filter, its counts and the library's length change only with a content revision: a
        // filter change or a reading-state transition both advance it.
        statusFilter = loaded.statusFilter
        statusCounts = LibraryStatusCounts(loaded.statusCounts)
        totalDurationMs = loaded.totalDurationMs
        isFilteredEmpty = loaded.isFilteredEmpty
    }

    /// Writes only a real change, so an equal value doesn't invalidate the views reading it.
    private func assignIfChanged<Value: Equatable>(
        _ keyPath: ReferenceWritableKeyPath<LibraryObserver, Value>,
        _ value: Value
    ) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }

    /// Publishes a contributor list only when it changed, so a new revision that changed only the
    /// books doesn't invalidate the Authors and Narrators lists.
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

    /// Bridge `Map<BookId, BookCardStatus>` → `[String: LibraryCardState]`, keyed like `mapProgress`.
    private func mapStatus(_ raw: [BookId: BookCardStatus]) -> [String: LibraryCardState] {
        var result: [String: LibraryCardState] = [:]
        for (key, value) in raw {
            result[key.value] = LibraryCardState.from(value)
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

/// Remembers the last `LibraryUiState.Loaded.contentRevision` applied, so an observer re-maps the
/// bridged content lists only when the shared ViewModel says they changed. Pure, so it's unit-tested
/// without live Kotlin state.
struct ContentRevisionGate {
    private(set) var applied: Int64?

    /// Records `revision` and returns true when it differs from the last one applied.
    mutating func advance(to revision: Int64) -> Bool {
        guard revision != applied else { return false }
        applied = revision
        return true
    }
}
