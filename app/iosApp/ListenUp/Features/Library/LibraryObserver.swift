import ListenupContract
import SwiftUI
import Shared

/// Observes `LibraryViewModel` — flattens the sealed `LibraryUiState` into flat
/// `@Observable` properties for all four library tabs. Thin over `FlowBridge`.
@Observable
@MainActor
final class LibraryObserver {
    // MARK: - Flattened state

    private(set) var books: [BookRow] = []
    /// `books` cut into index-letter runs (Title, Author or Series sorts); empty for any other sort.
    private(set) var bookLetters: [LetterRun] = []
    private(set) var booksSortState: SortState?
    private(set) var series: [SeriesRow] = []
    private(set) var seriesProgress: [String: SeriesProgressState] = [:]
    private(set) var seriesSortState: SortState?
    /// `series` cut into index-letter runs on a name sort (article-aware); empty otherwise.
    private(set) var seriesLetters: [LetterRun] = []
    /// `series` sliced by `seriesLetters` for the tab, once per content revision, as `authorSections` is.
    private(set) var seriesSections: [LetterRun.Section<SeriesRow>] = []
    private(set) var authors: [ContributorRow] = []
    /// `authors` cut into index-letter runs on a name sort; empty otherwise.
    private(set) var authorLetters: [LetterRun] = []
    /// `authors` sliced by `authorLetters` for the list, sliced once per content revision rather than
    /// in a view body (2026-09-29 iOS audit, performance).
    private(set) var authorSections: [LetterRun.Section<ContributorRow>] = []
    private(set) var authorsSortState: SortState?
    private(set) var narrators: [ContributorRow] = []
    /// `narrators` cut into index-letter runs on a name sort; empty otherwise.
    private(set) var narratorLetters: [LetterRun] = []
    /// `narrators` sliced by `narratorLetters`, as `authorSections` is.
    private(set) var narratorSections: [LetterRun.Section<ContributorRow>] = []
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
    /// What the status strip above the Books grid says; nil hides it. Moves on sync ticks, not content,
    /// so it is mapped outside the revision gate.
    private(set) var statusStrip: LibraryStatusStripModel?
    /// The library's first population, or this device's first catch-up, is still arriving. An empty grid
    /// reads this, never `isSyncing` (false for the whole seed): it is arriving, not empty.
    private(set) var isLibraryArriving: Bool = false
    private(set) var errorMessage: String?
    /// How many entries each section holds, for the iPad sidebar. Its own observable, written once per content
    /// revision and only on a real change, so the sidebar never re-renders on a position tick.
    let sectionCounts = SectionCountsHolder()

    @ObservationIgnored private var contentRevision = ContentRevisionGate()

    // view-model-owner: nobody — Dependencies.libraryViewModel is shared for the process.
    private let viewModel: LibraryViewModel
    private let bridge = FlowBridge()

    init(viewModel: LibraryViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
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

    func setStatusFilter(_ filter: BookStatusFilter) {
        viewModel.onEvent(event: LibraryUiEventStatusFilterChanged(filter: filter))
    }

    /// Discover's "Recently Added" link: Books sorted Added, newest first, not saved (spec §6.3).
    func showNewestFirst() {
        viewModel.onEvent(event: LibraryUiEventBooksShownNewestFirst.shared)
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
            assignIfChanged(\.bookStatus, mapStatus(loaded.bookStatus))
            assignIfChanged(\.seriesProgress, mapSeriesProgress(loaded.seriesProgress))
            assignIfChanged(\.isSyncing, loaded.isSyncing)
            assignIfChanged(\.statusStrip, LibraryStatusStripModel.from(loaded.statusStrip))
            assignIfChanged(\.isLibraryArriving, loaded.isLibraryArriving)
        case .error(let eType):
            isLoading = false
            errorMessage = eType.value.message
        }
    }

    /// Everything the content revision covers: the four sorted lists, their shared letter runs, and
    /// the intent that ordered them.
    private func applyContent(_ loaded: LibraryUiStateLoaded) {
        books = loaded.books.map { BookRow($0) }
        assignIfChanged(\.bookLetters, loaded.bookLetters.map { LetterRun($0) })
        booksSortState = loaded.booksSortState
        series = loaded.series.map { SeriesRow($0) }
        assignIfChanged(\.seriesLetters, loaded.seriesLetters.map { LetterRun($0) })
        seriesSections = LetterRun.sections(of: series, runs: seriesLetters)
        seriesSortState = loaded.seriesSortState
        authorsSortState = loaded.authorsSortState
        narratorsSortState = loaded.narratorsSortState
        applyContributors(
            loaded.authors.map { ContributorRow($0) },
            letters: loaded.authorLetters.map { LetterRun($0) },
            rows: \.authors,
            runs: \.authorLetters,
            sections: \.authorSections
        )
        applyContributors(
            loaded.narrators.map { ContributorRow($0) },
            letters: loaded.narratorLetters.map { LetterRun($0) },
            rows: \.narrators,
            runs: \.narratorLetters,
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
        sectionCounts.assign(LibrarySectionCountsValue(loaded.sectionCounts))
    }

    /// Writes only a real change, so an equal value doesn't invalidate the views reading it.
    private func assignIfChanged<Value: Equatable>(
        _ keyPath: ReferenceWritableKeyPath<LibraryObserver, Value>,
        _ value: Value
    ) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }

    /// Publishes a contributor list only when it or its letters changed, so a new revision that
    /// changed only the books doesn't invalidate the Authors and Narrators lists.
    private func applyContributors(
        _ newRows: [ContributorRow],
        letters newLetters: [LetterRun],
        rows: ReferenceWritableKeyPath<LibraryObserver, [ContributorRow]>,
        runs: ReferenceWritableKeyPath<LibraryObserver, [LetterRun]>,
        sections: ReferenceWritableKeyPath<LibraryObserver, [LetterRun.Section<ContributorRow>]>
    ) {
        guard newRows != self[keyPath: rows] || newLetters != self[keyPath: runs]
            || (self[keyPath: sections].isEmpty && !newRows.isEmpty) else { return }
        self[keyPath: rows] = newRows
        self[keyPath: runs] = newLetters
        self[keyPath: sections] = LetterRun.sections(of: newRows, runs: newLetters)
    }

    /// Bridge `Map<BookId, BookCardStatus>` → `[String: LibraryCardState]`. The `BookId` value-class
    /// key bridges as its wrapper type; keys are normalized to the book-id string the UI looks up by.
    private func mapStatus(_ raw: [BookId: BookCardStatus]) -> [String: LibraryCardState] {
        var result: [String: LibraryCardState] = [:]
        for (key, value) in raw {
            result[key.value] = LibraryCardState.from(value)
        }
        return result
    }

    /// Bridge `Map<SeriesId, SeriesProgress>` → `[String: SeriesProgressState]`.
    /// The `SeriesId` value-class key bridges as its wrapper type, matching `mapStatus`.
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
