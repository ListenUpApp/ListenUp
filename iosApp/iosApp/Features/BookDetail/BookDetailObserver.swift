import SwiftUI
import Shared

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
    private let playbackManager: PlaybackManager
    private let audioPlayer: AudioPlayer
    private let downloadService: DownloadService
    private let bridge = FlowBridge()
    private var observingDownloadForBookId: String?

    init(
        viewModel: BookDetailViewModel,
        playbackManager: PlaybackManager,
        audioPlayer: AudioPlayer,
        downloadService: DownloadService
    ) {
        self.viewModel = viewModel
        self.playbackManager = playbackManager
        self.audioPlayer = audioPlayer
        self.downloadService = downloadService
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
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
        playbackManager.activateBook(bookId: book.id)
        Task {
            guard let result = try? await playbackManager.prepareForPlayback(bookId: book.id) else { return }
            try? await playbackManager.startPlayback(
                player: audioPlayer,
                resumePositionMs: result.resumePositionMs,
                resumeSpeed: result.resumeSpeed
            )
        }
    }

    func downloadBook() {
        guard let book else { return }
        downloadError = nil
        Task {
            do {
                let result = try await downloadService.downloadBook(bookId: book.id)
                if let error = result as? DownloadResultError {
                    downloadError = error.message
                }
            } catch {
                downloadError = error.localizedDescription
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
            if let bookId = r.book.idString as String?, observingDownloadForBookId != bookId {
                observeDownloadStatus(bookId: bookId)
            }
        case .error(let e):
            isLoading = false
            error = e.message
        }
    }

    private func observeDownloadStatus(bookId: String) {
        observingDownloadForBookId = bookId
        bridge.bind(downloadService.observeBookStatus(bookId: bookId)) { [weak self] status in
            guard let self else { return }
            downloadProgress = status.progress
            isDownloaded = status.isFullyDownloaded
            switch status.state {
            case BookDownloadState.completed: downloadState = .completed
            case BookDownloadState.downloading: downloadState = .downloading
            case BookDownloadState.queued: downloadState = .queued
            case BookDownloadState.failed: downloadState = .failed
            case BookDownloadState.partial: downloadState = .partial
            default: downloadState = .notDownloaded
            }
        }
    }
}
