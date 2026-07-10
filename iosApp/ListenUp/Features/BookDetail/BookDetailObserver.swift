import SwiftUI
import ListenupContract
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

/// A collection flattened for the collection-picker sheet (admin-only).
struct CollectionRow: Identifiable, Equatable {
    let id: String
    let name: String
}

/// The book fields the hero renders, projected to native values so the hero never
/// re-bridges the Kotlin `BookDetail` per SwiftUI diff (cover lookup + series-pill nav).
struct BookDetailHeaderModel: Equatable {
    let coverBookId: String
    let coverPath: String?
    let coverBlurHash: String?
    let seriesId: String?
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
    private(set) var tags: [FacetChip] = []
    private(set) var moods: [FacetChip] = []

    // MARK: - Projected from `book`

    // Native value projections set once in `apply` `.ready`, never re-bridged in `body`.
    // BookDetail recomposes on every playback/download tick — reading these off the live
    // bridged object each render re-bridged its strings (and, for `audioFormat`, the whole
    // `audioFiles` collection) across the K/N boundary for a value that's static per book.
    private(set) var title: String = ""
    private(set) var authors: String = ""
    private(set) var duration: String = ""
    private(set) var durationMs: Int64 = 0
    private(set) var asin: String?
    private(set) var publisher: String?
    private(set) var language: String?
    /// The hero's book-derived fields (cover + series-pill nav), projected so the hero
    /// never re-bridges the raw `BookDetail`.
    private(set) var header: BookDetailHeaderModel?

    /// Pre-formatted audio-format display strings (Format / Bitrate / Sample rate / Channels),
    /// derived from the book's primary audio file. Fields are nil when their datum is absent.
    private(set) var audioFormat = AudioFormatDisplay(format: nil, bitrate: nil, sampleRate: nil, channels: nil)

    /// Pre-built share link for this book. Populated once per book load via `buildShareURL`.
    private(set) var shareURL: URL?

    // MARK: - Download state

    private(set) var downloadState: DownloadUIState = .notDownloaded
    private(set) var downloadProgress: Float = 0
    private(set) var isDownloaded: Bool = false
    private(set) var downloadError: String?

    // MARK: - Connectivity (from the shared `BookAvailability`, on `.ready`)

    /// Play is possible — the book is downloaded OR the server is reachable to stream. Defaults
    /// true so the button is never spuriously disabled before the first state arrives.
    private(set) var canPlay: Bool = true
    /// Download is possible — the server is reachable. Defaults true (see `canPlay`).
    private(set) var canDownload: Bool = true
    /// The server is unreachable AND the book isn't downloaded — drives the offline banner.
    private(set) var showServerWarning: Bool = false

    // MARK: - Documents

    private(set) var documents: [DocumentRow] = []
    private(set) var openingDocIds: Set<String> = []
    /// Set when a tapped PDF is ready; drives `.fullScreenCover`. Nil dismisses the reader.
    var documentToOpen: ReaderDocument?
    /// Set when a non-PDF document is tapped; drives a "coming soon" alert.
    var showComingSoon: Bool = false

    // MARK: - Curation & progress state

    private(set) var showShelfPicker: Bool = false
    private(set) var isAddingToShelf: Bool = false
    private(set) var shelfError: String?
    private(set) var myShelves: [ShelfRow] = []
    private(set) var isAdmin: Bool = false
    private(set) var showCollectionPicker: Bool = false
    private(set) var isAddingToCollection: Bool = false
    private(set) var collectionError: String?
    private(set) var allCollections: [CollectionRow] = []
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
            self?.allShelves = shelves.map { ShelfRow(id: $0.idString, name: $0.name, containsBook: false) }
            self?.recomputeShelfRows()
        }
        bridge.bind(viewModel.shelvesContainingBook) { [weak self] shelves in
            self?.shelfIdsContainingBook = Set(shelves.map { $0.idString })
            self?.recomputeShelfRows()
        }
        bridge.bind(viewModel.collections) { [weak self] collections in
            self?.allCollections = collections.map { CollectionRow(id: $0.id, name: $0.name) }
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

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func loadBook(bookId: String) {
        viewModel.loadBook(bookId: bookId)
    }

    func play() {
        guard let book else { return }
        playerCoordinator.play(bookId: book.idString)
    }

    /// Retry the server connection (re-opens the SSE firehose) — the offline banner's Retry.
    /// The shared `retryConnection` folds failures itself; reachability recovers via the firehose.
    func retryConnection() {
        viewModel.retryConnection()
    }

    func downloadBook() {
        guard let book else { return }
        downloadError = nil
        Task {
            if (try? await downloadService.downloadBookOrNull(bookId: book.id)) == nil {
                // Failure was folded + logged in Kotlin; surface a generic message (no AppResult here).
                downloadError = String(localized: "book.detail_download_failed")
                Log.error("downloadBook failed for \(book.idString)")
            }
        }
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

    // MARK: - Collection picker (admin)

    func openCollectionPicker() { viewModel.showCollectionPicker() }
    func closeCollectionPicker() { viewModel.hideCollectionPicker() }
    func addToCollection(collectionId: String) { viewModel.addBookToCollection(collectionId: collectionId) }
    func createCollectionAndAdd(name: String) { viewModel.createCollectionAndAddBook(name: name) }
    func clearCollectionError() { viewModel.clearCollectionError() }

    // MARK: - Documents

    func openDocument(docId: String) { viewModel.onOpenDocument(docId: docId) }
    func dismissReader() { documentToOpen = nil }
    func dismissComingSoon() { showComingSoon = false }

    // MARK: - Progress

    func discardProgress() { viewModel.discardProgress() }

    func markFinished() {
        let ts = Self.markCompleteTimestamps(
            startedAtMs: startedAtMs,
            now: Int64(Date().timeIntervalSince1970 * 1000)
        )
        viewModel.markComplete(startedAt: ts.start, finishedAt: ts.finish)
    }

    /// Pure: started defaults to `now` when unknown; finished is always `now`.
    nonisolated static func markCompleteTimestamps(startedAtMs: Int64?, now: Int64) -> (start: Int64, finish: Int64) {
        (start: startedAtMs ?? now, finish: now)
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
            applyBook(r.book)
            subtitle = r.subtitle
            series = r.series
            bookDescription = r.descriptionText
            narrators = r.narrators
            year = r.year.map { Int($0) }
            rating = r.rating
            progress = r.progress
            timeRemaining = r.timeRemainingFormatted
            isComplete = r.isComplete
            chapters = r.chapters.map { BookChapterRow($0) }
            genres = Array(r.genresList)
            tags = r.tags.map { FacetChip(id: $0.id, name: $0.name) }
            moods = r.moods.map { FacetChip(id: $0.id, name: $0.name) }
            showShelfPicker = r.showShelfPicker
            isAddingToShelf = r.isAddingToShelf
            shelfError = r.shelfError
            isAdmin = r.isAdmin
            showCollectionPicker = r.showCollectionPicker
            isAddingToCollection = r.isAddingToCollection
            collectionError = r.collectionError
            startedAtMs = r.startedAtMs
            isMarkingComplete = r.isMarkingComplete
            isDiscardingProgress = r.isDiscardingProgress
            isRestarting = r.isRestarting
            canPlay = r.canPlay
            canDownload = r.canDownload
            showServerWarning = r.showServerWarning
        case .error(let e):
            isLoading = false
            error = e.message
        case .unknown:
            Log.error("Unexpected BookDetailUiState case")
            isLoading = false
            error = String(localized: "common.something_went_wrong")
        }
    }

    /// Projects the bridged `BookDetail` to native values once, off the `body` diff path.
    /// Cover/series-pill fields, scalars, and the audio-format summary are all snapshotted
    /// here so the detail screen never re-bridges the Kotlin object on a playback/download tick.
    private func applyBook(_ book: BookDetail) {
        self.book = book
        title = book.title
        authors = book.authorNames
        duration = book.formatDuration()
        durationMs = book.duration
        asin = book.asin
        publisher = book.publisher
        // Show the real language name ("English"), not the stored code ("en").
        language = book.language.map(LanguageName.display)
        header = BookDetailHeaderModel(
            coverBookId: book.idString,
            coverPath: book.coverPath,
            coverBlurHash: book.coverBlurHash,
            seriesId: book.seriesId
        )
        audioFormat = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.bookdetail
            .audioFormatDisplay(files: book.audioFiles)
        heroAuthors = book.authors.map { CastMember(id: $0.id, name: $0.name, roles: Array($0.roles)) }
        heroNarrators = book.narrators.map { CastMember(id: $0.id, name: $0.name, roles: Array($0.roles)) }
        if observingDownloadForBookId != book.idString {
            observingDownloadForBookId = book.idString
            observeDownloadStatus(bookId: book.idString)
            Task { await buildShareURL(bookId: book.idString) }
        }
    }

    private func buildShareURL(bookId: String) async {
        // Use the RPC-backed server identity, not the legacy `getInstance` REST path: the Kotlin
        // server responds a bare (non-enveloped) body there, so decoding it as `ApiResponse<Instance>`
        // threw `EnvelopeMismatchException` on every book-detail load. `getServerInfo` is pure RPC
        // and carries the `remoteUrl` + `instanceId` the share link needs. The embedded `serverUrl`
        // is advisory (display / future connect), so the WAN `remoteUrl` is the right value.
        guard let info = try? await Dependencies.shared.instanceRepository.getServerInfoOrNull(forceRefresh: false)
        else { return }
        let trimmed = info.remoteUrl.map { $0.hasSuffix("/") ? String($0.dropLast()) : $0 }
        let raw = ShareLinkCodec.shared.encode(
            target: ShareTargetBook(
                bookId: BookId(value: bookId),
                serverInstanceId: info.instanceId,
                serverUrl: trimmed
            )
        )
        shareURL = URL(string: raw)
    }

    private func observeDownloadStatus(bookId: String) {
        bridge.bind(downloadService.observeBookStatus(bookId: BookId(value: bookId))) { [weak self] status in
            self?.applyDownloadStatus(status)
        }
    }

    private func applyNavAction(_ action: BookDetailNavAction) {
        switch onEnum(of: action) {
        case .openDocumentViewer(let open):
            documentToOpen = ReaderDocument(localPath: open.localPath, title: title)
        case .showViewerComingSoon:
            showComingSoon = true
        case .unknown:
            Log.error("Unexpected BookDetailNavAction case")
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
        case .unknown:
            Log.error("Unexpected BookDownloadStatus case")
            downloadState = .notDownloaded
            downloadProgress = 0
            isDownloaded = false
        }
    }
}
