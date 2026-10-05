import SwiftUI
import Shared

/// Observes `SeriesDetailViewModel`, flattening `SeriesDetailUiState` into flat
/// `@Observable` properties, and drives playback via `PlayerCoordinator`.
@Observable
@MainActor
final class SeriesDetailObserver {
    private(set) var isLoading: Bool = true
    private(set) var error: String?
    private(set) var seriesName: String = ""
    private(set) var seriesDescription: String?
    /// Every author across the series' books, deduped server-side by id in first-appearance order.
    /// Mapped to a native value type at the observer boundary so it never re-bridges in a `ForEach`.
    private(set) var seriesAuthors: [SeriesAuthor] = []
    private(set) var coverPath: String?
    private(set) var totalDuration: String = ""
    private(set) var books: [BookRow] = []
    private(set) var bookProgress: [String: Float] = [:]
    private(set) var finishedBookIds: Set<String> = []
    private(set) var finishedCount: Int = 0
    private(set) var resumeTarget: String?

    // MARK: - Hierarchy

    /// The series above this one, root first — the breadcrumb. Empty on a top-level series.
    private(set) var ancestors: [SeriesCrumbItem] = []
    /// The direct sub-series, in sibling order.
    private(set) var childSeries: [ChildSeriesCard] = []
    /// The book list's groups, in reading order. A flat page has one, shown without a heading.
    private(set) var bookGroups: [SeriesBookGroup] = []
    /// The book Continue resumes on a grouped page, with the series it is listed under.
    private(set) var resume: SeriesResumeInfo?
    /// Whether the reader may change the hierarchy (admin, or the edit permission).
    private(set) var canEditHierarchy: Bool = false
    /// Whether the server can be reached; adding a sub-series needs it.
    private(set) var isOnline: Bool = true
    /// The "Add sub-series" sheet.
    private(set) var addSubSeries = AddSubSeriesSheetModel()

    /// True when the page has sub-series — its books are grouped, and Continue names the book.
    var isGrouped: Bool { !childSeries.isEmpty }

    /// "4 series · 23 books" on a grouped page; "4 books" otherwise.
    var countLine: String {
        isGrouped
            ? SeriesHierarchyText.seriesAndBooks(seriesCount: childSeries.count, bookCount: bookCount)
            : SeriesHierarchyText.books(bookCount)
    }

    /// The line under a grouped page's Continue — "Mistborn Era 1 · Book 3". Nil on a flat page.
    var continueSubtitle: String? {
        guard isGrouped, let resume else { return nil }
        return SeriesHierarchyText.continueWhere(seriesName: resume.seriesName, sequence: resume.sequence)
    }

    var bookCount: Int { books.count }

    /// The book the Continue CTA will start, with its sequence (for the title).
    private var resumeBook: BookRow? { books.first { $0.id == resumeTarget } }

    /// True once the user has any progress in the series — at least one in-progress book
    /// ([bookProgress] holds in-progress books only) or at least one finished book. When false
    /// the [resumeTarget] is merely the first book, so the CTA must read "Start", not "Continue".
    private var hasStarted: Bool { !bookProgress.isEmpty || finishedCount > 0 }

    /// Continue-CTA label, derived from resume + progress state.
    var continueButtonTitle: String {
        if isGrouped { return Self.groupedContinueLabel(resumeTitle: resume?.title, hasStarted: hasStarted) }
        return Self.continueLabel(
            hasBooks: !books.isEmpty,
            resumeTargetIsNil: resumeTarget == nil,
            hasStarted: hasStarted,
            sequence: resumeBook?.sequence
        )
    }

    /// The CTA on a page with sub-series. It names the book — "Continue The Hero of Ages" — because a
    /// bare "Continue Book 3" is ambiguous across four series; for the same reason a never-started
    /// page reads "Start listening" (the line underneath says where), and a finished one "Listen again".
    nonisolated static func groupedContinueLabel(resumeTitle: String?, hasStarted: Bool) -> String {
        guard let resumeTitle else { return String(localized: "series.listen_again") }
        return hasStarted
            ? SeriesHierarchyText.continueTitle(bookTitle: resumeTitle)
            : String(localized: "series.start_listening")
    }

    /// Pure CTA-label decision, extracted so it is unit-testable without constructing the
    /// observer (which needs live KMP state). A never-started series (`!hasStarted`) reads
    /// "Start Book N" / "Start Listening" rather than "Continue", even though a [resumeTarget]
    /// (the first book) always exists.
    nonisolated static func continueLabel(
        hasBooks: Bool,
        resumeTargetIsNil: Bool,
        hasStarted: Bool,
        sequence: String?
    ) -> String {
        if !hasBooks { return String(localized: "series.start_listening") }
        if resumeTargetIsNil { return String(localized: "series.listen_again") }
        if let seq = sequence, !seq.isEmpty {
            return String(format: String(localized: hasStarted ? "series.continue_book" : "series.start_book"), seq)
        }
        return String(localized: hasStarted ? "series.continue" : "series.start_listening")
    }

    /// True when `bookId` is the active book (playing OR buffering toward playing), so the row's
    /// glyph shows "pause" during the startup buffer instead of a play button that pauses.
    func isPlaying(_ bookId: String) -> Bool {
        playerCoordinator.currentBookId == bookId && playerCoordinator.isPlaybackActive
    }

    func progress(for bookId: String) -> Float? { bookProgress[bookId] }
    func isFinished(_ bookId: String) -> Bool { finishedBookIds.contains(bookId) }

    private let viewModel: SeriesDetailViewModel
    private let playerCoordinator: PlayerCoordinator
    private let bridge = FlowBridge()

    init(viewModel: SeriesDetailViewModel, playerCoordinator: PlayerCoordinator) {
        self.viewModel = viewModel
        self.playerCoordinator = playerCoordinator
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.addSubSeries) { [weak self] in self?.applyAddSubSeries($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func loadSeries(seriesId: String) { viewModel.loadSeries(seriesId: seriesId) }

    /// Start/resume the series at its resume target.
    func continueSeries() {
        guard let resumeTarget else { return }
        playerCoordinator.play(bookId: resumeTarget)
    }

    /// Play `bookId`, or pause/resume it if it's already the current book.
    func playBook(_ bookId: String) {
        if playerCoordinator.currentBookId == bookId {
            playerCoordinator.togglePlayback()
        } else {
            playerCoordinator.play(bookId: bookId)
        }
    }

    /// Fold or unfold a sub-series' group of books.
    func toggleGroup(_ seriesId: String) { viewModel.toggleSection(seriesId: seriesId) }

    /// Drive the "Add sub-series" sheet.
    func send(_ action: AddSubSeriesAction) { viewModel.onAddSubSeriesEvent(event: action.kotlinEvent) }

    // MARK: - State mapping

    private func applyAddSubSeries(_ state: AddSubSeriesUiState) {
        addSubSeries = AddSubSeriesSheetModel(state)
        // A refusal already reached the reader through the shared error bus (GlobalErrorObserver's
        // alert); presenting it again here would say it twice (iosApp rule 10).
        if AddSubSeriesSheetModel.hasError(state) { send(.errorDismissed) }
    }

    private func apply(_ state: SeriesDetailUiState) {
        switch state.sealedType() {
        case .idle, .loading:
            isLoading = true
            error = nil
        case .ready(let rType):
            let r = rType.value
            isLoading = false
            error = nil
            seriesName = r.seriesName
            seriesDescription = r.seriesDescription
            seriesAuthors = r.seriesAuthors.map { SeriesAuthor(id: $0.id, name: $0.name) }
            coverPath = r.coverPath
            totalDuration = r.formatTotalDuration()
            // The model holds a number; BookRow shows text. Formatted here, at the display
            // edge, exactly as the Compose side does.
            books = r.books.map { BookRow($0, sequence: SeriesSequenceFormatting.label($0.series.first?.sequence)) }
            bookProgress = mapBookProgress(r.bookProgress)
            finishedBookIds = Set(r.finishedBookIds.map { String(describing: $0) })
            finishedCount = Int(r.finishedCount)
            ancestors = r.ancestors.map(SeriesCrumbItem.init)
            childSeries = r.childSeries.map(ChildSeriesCard.init)
            bookGroups = r.bookSections.map(SeriesBookGroup.init)
            resume = r.resumeBook.map(SeriesResumeInfo.init)
            canEditHierarchy = r.canEditHierarchy
            isOnline = r.isOnline
            if let raw = r.resumeTarget {
                resumeTarget = String(describing: raw)
            } else {
                resumeTarget = nil
            }
        case .error(let eType):
            let e = eType.value
            isLoading = false
            error = e.message
        }
    }

    /// `Map<BookId, Float>` arrives as `[BookId: Float]` over the Swift Export
    /// boundary — the `BookId` value-class key bridges as its wrapper type. Keys are
    /// normalized to the book-id string the UI looks up by.
    private func mapBookProgress(_ raw: [BookId: Float]) -> [String: Float] {
        var result: [String: Float] = [:]
        for (key, value) in raw {
            result[key.value] = value
        }
        return result
    }

}

/// A native, value-typed series author for SwiftUI. KMP `BookContributor` is Swift Export-bridged and must
/// never be fed into a `ForEach`/`List` (it re-bridges every diff), so it's mapped to this struct at
/// the observer boundary. Mirrors `BookRow`/`CastMember`.
struct SeriesAuthor: Identifiable, Hashable {
    let id: String
    let name: String
}
