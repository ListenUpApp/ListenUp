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
    private(set) var chapters: [ChapterUiModel] = []
    private(set) var genres: [String] = []

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
            guard let result = try? await downloadService.downloadBook(bookId: book.id) else { return }
            switch onEnum(of: result) {
            case .success:
                break
            case .failure(let failure):
                downloadError = failure.error.message
            }
        }
    }

    func cancelDownload() {
        guard let book else { return }
        Task { try? await downloadService.cancelDownload(bookId: book.id) }
    }

    func deleteDownload() {
        guard let book else { return }
        Task { try? await downloadService.deleteDownload(bookId: book.id) }
    }

    // MARK: - Shelf picker

    func openShelfPicker() { viewModel.showShelfPicker() }
    func closeShelfPicker() { viewModel.hideShelfPicker() }
    func addToShelf(shelfId: String) { viewModel.addBookToShelf(shelfId: shelfId) }
    func createShelfAndAdd(name: String) { viewModel.createShelfAndAddBook(name: name) }
    func clearShelfError() { viewModel.clearShelfError() }

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
            bookDescription = r.description
            narrators = r.narrators
            year = r.year?.intValue
            rating = r.rating?.doubleValue
            progress = r.progress?.floatValue
            timeRemaining = r.timeRemainingFormatted
            isComplete = r.isComplete
            chapters = Array(r.chapters)
            genres = Array(r.genresList)
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
