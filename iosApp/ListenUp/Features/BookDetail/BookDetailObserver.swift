import SwiftUI
@preconcurrency import Shared

/// Download state for the UI, mapped from Kotlin's `BookDownloadState`.
enum DownloadUIState {
    case notDownloaded, queued, downloading, completed, partial, failed
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

    // MARK: - Dependencies

    private let viewModel: BookDetailViewModel
    private let playerCoordinator: PlayerCoordinator
    private let downloadService: DownloadService
    private let bridge = FlowBridge()
    private var observingDownloadForBookId: String?

    init(
        viewModel: BookDetailViewModel,
        playerCoordinator: PlayerCoordinator,
        downloadService: DownloadService
    ) {
        self.viewModel = viewModel
        self.playerCoordinator = playerCoordinator
        self.downloadService = downloadService
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
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
            if observingDownloadForBookId != r.book.idString {
                observingDownloadForBookId = r.book.idString
                observeDownloadStatus(bookId: r.book.idString)
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
