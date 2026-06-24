import SwiftUI
@preconcurrency import Shared

/// Download state for the UI, mapped from Kotlin's `BookDownloadState`.
enum DownloadUIState {
    case notDownloaded, queued, downloading, completed, partial, failed
}

/// A user shelf flattened for the shelf-picker sheet, with this book's membership.
struct ShelfRow: Identifiable, Equatable {
    let id: String
    let name: String
    let containsBook: Bool
}

/// Observes `BookDetailViewModel` — flattens the sealed `BookDetailUiState` into
/// flat `@Observable` properties, plus a download-status secondary flow. Thin over `FlowBridge`.
@Observable
@MainActor
final class BookDetailObserver {
    // MARK: - Flattened state

    private(set) var isLoading: Bool = true
    private(set) var error: String?
    private(set) var book: BookDetail?
    private(set) var subtitle: String?
    private(set) var series: String?
    private(set) var bookDescription: String = ""
    private(set) var narrators: String = ""
    private(set) var year: Int?
    private(set) var rating: Double?
    private(set) var progress: Float?
    private(set) var timeRemaining: String?
    private(set) var isComplete: Bool = false
    private(set) var chapters: [BookChapterRow] = []
    /// Tappable author/narrator chips for the hero, projected to native `CastMember` so the
    /// hero's `ForEach` never re-bridges the Kotlin `BookContributor`s.
    private(set) var heroAuthors: [CastMember] = []
    private(set) var heroNarrators: [CastMember] = []
    private(set) var genres: [String] = []
    private(set) var tags: [String] = []
    private(set) var moods: [String] = []

    // MARK: - Derived from `book`

    var title: String { book?.title ?? "" }
    var authors: String { book?.authorNames ?? "" }
    var coverPath: String? { book?.coverPath }
    var coverBlurHash: String? { book?.coverBlurHash }
    var duration: String { book?.formatDuration() ?? "" }
    var durationMs: Int64 { book?.duration ?? 0 }

    // MARK: - Download state

    private(set) var downloadState: DownloadUIState = .notDownloaded
    private(set) var downloadProgress: Float = 0
    private(set) var isDownloaded: Bool = false
    private(set) var downloadError: String?

    // MARK: - Documents

    private(set) var documents: [DocumentRow] = []
    private(set) var openingDocIds: Set<String> = []
    /// Set when a tapped PDF is ready; drives `.fullScreenCover`. Nil dismisses the reader.
    var documentToOpen: ReaderDocument?
    /// Set when a non-PDF document is tapped; drives a "coming soon" alert.
    var showComingSoon: Bool = false

    // MARK: - Curation & progress state

    /// Per-book accent, derived from cover art. Coral until extraction resolves.
    private(set) var tint: Color = .listenUpOrange
    private(set) var showShelfPicker: Bool = false
    private(set) var isAddingToShelf: Bool = false
    private(set) var shelfError: String?
    private(set) var myShelves: [ShelfRow] = []
    private(set) var startedAtMs: Int64?
    private(set) var isMarkingComplete: Bool = false
    private(set) var isDiscardingProgress: Bool = false
    private(set) var isRestarting: Bool = false

    // MARK: - Dependencies

    private let viewModel: BookDetailViewModel
    private let playerCoordinator: PlayerCoordinator
    private let downloadService: DownloadService
    private let bridge = FlowBridge()
    private var observingDownloadForBookId: String?
    private var tintForBookId: String?

    // Latest raw shelf inputs; `myShelves` is recomputed when either updates.
    private var allShelves: [ShelfRow] = []
    private var shelfIdsContainingBook: Set<String> = []

    init(
        viewModel: BookDetailViewModel,
        playerCoordinator: PlayerCoordinator,
        downloadService: DownloadService
    ) {
        self.viewModel = viewModel
        self.playerCoordinator = playerCoordinator
        self.downloadService = downloadService
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.myShelves) { [weak self] shelves in
            self?.allShelves = shelves.map { ShelfRow(id: $0.id, name: $0.name, containsBook: false) }
            self?.recomputeShelfRows()
        }
        bridge.bind(viewModel.shelvesContainingBook) { [weak self] shelves in
            self?.shelfIdsContainingBook = Set(shelves.map { $0.id })
            self?.recomputeShelfRows()
        }
        bridge.bind(viewModel.documents) { [weak self] docs in
            self?.documents = docs.map { DocumentRow($0) }
        }
        bridge.bind(viewModel.openingDocumentIds) { [weak self] ids in
            self?.openingDocIds = Set(ids)
        }
        bridge.bind(viewModel.navActions) { [weak self] action in
            self?.applyNavAction(action)
        }
    }

    /// Fold the latest `myShelves` + containing-book membership into `[ShelfRow]`.
    private func recomputeShelfRows() {
        myShelves = allShelves.map {
            ShelfRow(id: $0.id, name: $0.name, containsBook: shelfIdsContainingBook.contains($0.id))
        }
    }

    deinit {
        // `BookDetailObserver` is held in SwiftUI `@State` on a `@MainActor`-isolated
        // view, so ARC dealloc always fires on the main thread — `assumeIsolated` is sound.
        MainActor.assumeIsolated { stopObserving() }
    }

    func stopObserving() {
        bridge.cancelAll()
    }

    // MARK: - Actions

    func loadBook(bookId: String) {
        viewModel.loadBook(bookId: bookId)
    }

    func play() {
        guard let book else { return }
        playerCoordinator.play(bookId: book.idString)
    }

    func downloadBook() {
        guard let book else { return }
        downloadError = nil
        Task {
            do {
                let result = try await downloadService.downloadBook(bookId: book.id)
                switch onEnum(of: result) {
                case .success:
                    break
                case .failure(let failure):
                    downloadError = failure.error.message
                }
            } catch {
                // A *thrown* error (vs. the typed `.failure` above) is unexpected — surface
                // it instead of the old `try?` that silently dropped it and left the button
                // looking idle. Cancellation (the view went away) is intentionally silent.
                if let message = Self.downloadThrowMessage(for: error) {
                    Log.error("Download failed for \(book.idString)", error: error)
                    downloadError = message
                }
            }
        }
    }

    /// Maps a *thrown* download error to a user-facing message, or `nil` when the throw
    /// is mere cancellation (the user navigated away) and nothing should surface.
    nonisolated static func downloadThrowMessage(for error: Error) -> String? {
        error is CancellationError ? nil : String(localized: "common.something_went_wrong")
    }

    func cancelDownload() {
        guard let book else { return }
        Task {
            do {
                try await downloadService.cancelDownload(bookId: book.id)
            } catch is CancellationError {
            } catch {
                Log.error("Cancel download failed for \(book.idString)", error: error)
            }
        }
    }

    func deleteDownload() {
        guard let book else { return }
        Task {
            do {
                try await downloadService.deleteDownload(bookId: book.id)
            } catch is CancellationError {
            } catch {
                Log.error("Delete download failed for \(book.idString)", error: error)
            }
        }
    }

    // MARK: - Shelf picker

    func openShelfPicker() { viewModel.showShelfPicker() }
    func closeShelfPicker() { viewModel.hideShelfPicker() }
    func addToShelf(shelfId: String) { viewModel.addBookToShelf(shelfId: shelfId) }
    func createShelfAndAdd(name: String) { viewModel.createShelfAndAddBook(name: name) }
    func clearShelfError() { viewModel.clearShelfError() }

    // MARK: - Documents

    func openDocument(docId: String) { viewModel.onOpenDocument(docId: docId) }
    func dismissReader() { documentToOpen = nil }
    func dismissComingSoon() { showComingSoon = false }

    // MARK: - Progress

    func restart() { viewModel.restartBook() }
    func discardProgress() { viewModel.discardProgress() }

    func markFinished() {
        let ts = Self.markCompleteTimestamps(
            startedAtMs: startedAtMs,
            now: Int64(Date().timeIntervalSince1970 * 1000)
        )
        viewModel.markComplete(startedAt: KotlinLong(value: ts.start), finishedAt: KotlinLong(value: ts.finish))
    }

    /// Pure: started defaults to `now` when unknown; finished is always `now`.
    nonisolated static func markCompleteTimestamps(startedAtMs: Int64?, now: Int64) -> (start: Int64, finish: Int64) {
        (start: startedAtMs ?? now, finish: now)
    }

    // MARK: - Tint extraction

    /// Resolve the per-book accent. Uses the synchronous cache when warm; otherwise
    /// launches an off-actor decode and sets `tint` when it lands. Leaves coral on nil.
    private func resolveTint(bookId: String, coverPath: String?) {
        if let cached = CoverTintExtractor.shared.cached(bookId: bookId) {
            tint = cached.color
            return
        }
        Task { [weak self] in
            guard let self else { return }
            if let resolved = await CoverTintExtractor.shared.resolve(bookId: bookId, coverPath: coverPath) {
                self.tint = resolved.color
            }
        }
    }

    // MARK: - State mapping

    private func apply(_ state: BookDetailUiState) {
        switch onEnum(of: state) {
        case .loading:
            isLoading = true
            error = nil
        case .ready(let r):
            isLoading = false
            error = nil
            book = r.book
            subtitle = r.subtitle
            series = r.series
            bookDescription = r.descriptionText
            narrators = r.narrators
            year = r.year?.intValue
            rating = r.rating?.doubleValue
            progress = r.progress?.floatValue
            timeRemaining = r.timeRemainingFormatted
            isComplete = r.isComplete
            chapters = r.chapters.map { BookChapterRow($0) }
            heroAuthors = r.book.authors.map { CastMember(id: $0.id, name: $0.name, roles: Array($0.roles)) }
            heroNarrators = r.book.narrators.map { CastMember(id: $0.id, name: $0.name, roles: Array($0.roles)) }
            genres = Array(r.genresList)
            tags = r.tags.map { $0.name }
            moods = r.moods.map { $0.name }
            showShelfPicker = r.showShelfPicker
            isAddingToShelf = r.isAddingToShelf
            shelfError = r.shelfError
            startedAtMs = r.startedAtMs?.int64Value
            isMarkingComplete = r.isMarkingComplete
            isDiscardingProgress = r.isDiscardingProgress
            isRestarting = r.isRestarting
            if observingDownloadForBookId != r.book.idString {
                observingDownloadForBookId = r.book.idString
                observeDownloadStatus(bookId: r.book.idString)
            }
            if tintForBookId != r.book.idString {
                tintForBookId = r.book.idString
                resolveTint(bookId: r.book.idString, coverPath: r.book.coverPath)
            }
        case .error(let e):
            isLoading = false
            error = e.message
        }
    }

    private func observeDownloadStatus(bookId: String) {
        bridge.bind(downloadService.observeBookStatus(bookId: bookId)) { [weak self] status in
            self?.applyDownloadStatus(status)
        }
    }

    private func applyNavAction(_ action: BookDetailNavAction) {
        switch onEnum(of: action) {
        case .openDocumentViewer(let open):
            documentToOpen = ReaderDocument(localPath: open.localPath, title: title)
        case .showViewerComingSoon:
            showComingSoon = true
        }
    }

    /// Flatten the sealed `BookDownloadStatus` into the UI-facing download props.
    private func applyDownloadStatus(_ status: BookDownloadStatus) {
        switch onEnum(of: status) {
        case .notDownloaded:
            downloadState = .notDownloaded
            downloadProgress = 0
            isDownloaded = false
        case .inProgress(let s):
            downloadState = .downloading
            downloadProgress = s.progress
            isDownloaded = false
        case .completed:
            downloadState = .completed
            downloadProgress = 1
            isDownloaded = true
        case .failed:
            downloadState = .failed
            isDownloaded = false
        case .paused(let s):
            downloadState = .partial
            downloadProgress = s.totalBytes > 0
                ? Float(s.downloadedBytes) / Float(s.totalBytes)
                : 0
            isDownloaded = false
        }
    }
}
