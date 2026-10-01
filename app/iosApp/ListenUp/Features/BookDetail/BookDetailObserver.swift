import SwiftUI
import ListenupContract
@preconcurrency import Shared

/// Download state for the UI, mapped from Kotlin's `BookDownloadState`.
enum DownloadUIState {
    case notDownloaded, queued, downloading, waitingForWifi, completed, partial, failed
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
    /// Content hash of the current cover, folded into the cover's cache key so a re-scrape
    /// content-addresses the fresh cover instead of serving the stale id-stable local file.
    let coverHash: String?
    let seriesId: String?
}

/// What the screen does with one of the ViewModel's one-shot `BookDetailNavAction`s, as a native
/// value — so the mapping is testable without a live ViewModel behind it.
enum BookDetailNavReaction: Equatable {
    case openDocument(localPath: String)
    case showComingSoon
    /// The book was deleted from the server, folder and all: purge this device's copy and leave.
    case leaveDeletedBook
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
    private(set) var progress: Float?
    private(set) var timeRemaining: String?
    private(set) var isComplete: Bool = false
    private(set) var chapters: [BookChapterRow] = []
    /// Tappable author/narrator chips for the hero, projected to native `CastMember` so the
    /// hero's `ForEach` never re-bridges the Kotlin `BookContributor`s.
    private(set) var heroAuthors: [CastMember] = []
    private(set) var heroNarrators: [CastMember] = []
    private(set) var genres: [FacetChip] = []
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

    /// "Download on Wi-Fi Only" is on, the network is metered, and the download is parked.
    /// Derived by the shared ViewModel; iOS renders it rather than showing a spinner that lies.
    private(set) var isWaitingForWifi = false

    /// The last status seen, kept so `isWaitingForWifi` arriving second can re-derive the state —
    /// the two come from independent streams and either order is normal.
    private var latestDownloadStatus: BookDownloadStatus?
    private(set) var downloadProgress: Float = 0
    private(set) var isDownloaded: Bool = false
    private(set) var downloadError: String?

    // MARK: - Connectivity (from the shared `BookAvailability`, on `.ready`)

    /// Play is possible — the book is downloaded, or the evidence-based reachability signal says
    /// the server is usable right now. Defaults true so the button is never spuriously disabled
    /// before the first state arrives; heals the instant any traffic proves the server answers.
    private(set) var canPlay: Bool = true
    /// Download is possible — playback platform present AND the server is reachable per the same
    /// evidence-based signal as `canPlay`.
    private(set) var canDownload: Bool = true
    /// The server is genuinely unreachable AND the book isn't downloaded — drives the
    /// point-of-need banner that explains why Play/Download are disabled.
    private(set) var showServerWarning: Bool = false

    // MARK: - Held for review (admin inbox)

    /// Held for review: the page is triage-only (`BookDetailLayout.triage`). Never true for a member.
    private(set) var isHeld: Bool = false
    /// A release is in flight — Release shows its spinner.
    private(set) var isReleasingFromInbox: Bool = false
    var layout: BookDetailLayout { .forBook(isHeld: isHeld) }

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

    // MARK: - Delete book (admin)

    /// True while the delete is in flight — the menu entry goes quiet so a second press can't race it.
    private(set) var isDeletingBook: Bool = false
    /// Set once the server has deleted the book; the view leaves the screen on it.
    private(set) var didDeleteBook: Bool = false
    /// Audio file sizes, snapshotted per book so the confirmation never re-bridges `audioFiles`.
    private var audioFileSizes: [Int64] = []
    /// What ListenUp knows is in the book's folder — audio plus documents, which live there too.
    var trackedForDeletion: BookDeletion.Tracked {
        BookDeletion.Tracked(audioFileSizes: audioFileSizes, documentSizes: documents.map(\.size))
    }

    /// True while a play request for THIS book is in flight — drives the Resume/Play button's
    /// busy variant (spinner + "Preparing…" label, including to VoiceOver). Sourced directly from
    /// `PlayerCoordinator`'s native `PlayerPhase.preparing`, not a bridged Kotlin flow: iOS never
    /// binds the shared `PlaybackManager` (see `PlaybackModule.ios.kt`), so this is the
    /// platform-native equivalent of `PlaybackManager.preparingBookIdUi`. `@Observable` composes
    /// across the two objects, so this recomputes and republishes whenever `playerCoordinator.phase`
    /// changes — no separate subscription needed.
    var isPlayPending: Bool {
        Self.isPlayPending(phase: playerCoordinator.phase, bookId: book?.idString)
    }

    /// Pure: `phase` is `.preparing` for exactly `bookId`.
    nonisolated static func isPlayPending(phase: PlayerPhase, bookId: String?) -> Bool {
        guard let bookId, case .preparing(let state) = phase else { return false }
        return state.bookId == bookId
    }

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

    // MARK: - Delete book (admin)

    /// **Permanently deletes this book's folder from the server.** Admin-only: the menu entry is gated
    /// on `isAdmin` and the server refuses anyone else. A refusal reaches the user through the shared
    /// error bus — `ErrorAlertCenter`'s alert — so it is deliberately not presented a second time here.
    func deleteBook() { viewModel.deleteBook() }

    /// Clears a previous refusal so a fresh confirmation starts clean.
    func clearDeleteError() { viewModel.clearDeleteError() }

    // MARK: - Release (admin)

    /// Releases this held book to everyone. The view confirms first. On success the INBOX row leaves
    /// Room at once and `isHeld` turns false, so the page becomes the ordinary one; a refusal reaches
    /// the user through `ErrorAlertCenter`'s alert, as Delete Book's does.
    func releaseFromInbox() { viewModel.releaseFromInbox() }

    // MARK: - Progress

    func discardProgress() { viewModel.discardProgress() }

    /// Restart the book from the beginning, clearing current progress. `isRestarting` reflects the
    /// in-flight write; the shared VM resets progress/complete on success.
    func restartBook() { viewModel.restartBook() }

    /// Mark the book finished on the days the reader chose in the sheet. Days they left alone keep
    /// the instant the sheet opened with, so confirming untouched sends exactly what the one-tap
    /// finish always sent.
    func markFinished(started: Date, finished: Date) {
        let ts = Self.markCompleteTimestamps(
            started: started,
            finished: finished,
            startedAtMs: startedAtMs,
            now: Self.nowMs(),
            calendar: .current
        )
        viewModel.markComplete(startedAt: ts.start, finishedAt: ts.finish)
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Pure: the days the sheet opens on — the recorded start day (today if unknown), and today —
    /// as start-of-day `Date`s in [calendar]. Mirrors `FinishDates.initial` in sharedLogic.
    nonisolated static func finishDaysOpened(
        startedAtMs: Int64?,
        now: Int64,
        calendar: Calendar
    ) -> (started: Date, finished: Date) {
        (
            started: calendar.startOfDay(for: date(ms: startedAtMs ?? now)),
            finished: calendar.startOfDay(for: date(ms: now))
        )
    }

    /// Pure: why these days can't be saved, or nil — the shared `FinishDatesProblem`, by the rule of
    /// `FinishDates.problem` (whose `LocalDate`s don't cross Swift Export usefully).
    nonisolated static func finishDatesProblem(
        started: Date,
        finished: Date,
        now: Int64,
        calendar: Calendar
    ) -> FinishDatesProblem? {
        let today = calendar.startOfDay(for: date(ms: now))
        let startDay = calendar.startOfDay(for: started)
        let finishDay = calendar.startOfDay(for: finished)
        if startDay > today || finishDay > today { return .InTheFuture }
        if finishDay < startDay { return .FinishedBeforeStarted }
        return nil
    }

    /// Pure: the epoch milliseconds for the chosen days. An unchanged day keeps the instant it
    /// opened with; a changed one is the start of that day in [calendar]; the finish never precedes
    /// the start. Mirrors `FinishDates.toTimestamps`.
    nonisolated static func markCompleteTimestamps(
        started: Date,
        finished: Date,
        startedAtMs: Int64?,
        now: Int64,
        calendar: Calendar
    ) -> (start: Int64, finish: Int64) {
        let openedStart = startedAtMs ?? now
        let start = calendar.isDate(started, inSameDayAs: date(ms: openedStart))
            ? openedStart
            : ms(calendar.startOfDay(for: started))
        let finish = calendar.isDate(finished, inSameDayAs: date(ms: now))
            ? now
            : ms(calendar.startOfDay(for: finished))
        return (start: start, finish: max(finish, start))
    }

    private nonisolated static func date(ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
    private nonisolated static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    // MARK: - State mapping

    private func apply(_ state: BookDetailUiState) {
        switch state.sealedType() {
        case .loading:
            isLoading = true
            error = nil
        case .ready(let rType):
            let r = rType.value
            isLoading = false
            error = nil
            applyBook(r.book)
            subtitle = r.subtitle
            series = r.series
            bookDescription = r.descriptionText
            narrators = r.narrators
            year = r.year.map { Int($0) }
            progress = r.progress
            timeRemaining = r.timeRemainingFormatted
            isComplete = r.isComplete
            chapters = r.chapters.map { BookChapterRow($0) }
            genres = r.genres.map { FacetChip(id: $0.id, name: $0.name) }
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
            isDeletingBook = r.isDeletingBook
            canPlay = r.canPlay
            canDownload = r.canDownload
            showServerWarning = r.showServerWarning
            isHeld = r.isHeld
            isReleasingFromInbox = r.isReleasingFromInbox
            if isWaitingForWifi != r.isWaitingForWifi {
                isWaitingForWifi = r.isWaitingForWifi
                latestDownloadStatus.map { applyDownloadStatus($0) }
            }
        case .error(let eType):
            let e = eType.value
            isLoading = false
            error = e.error.message
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
            coverHash: book.coverHash,
            seriesId: book.seriesId
        )
        audioFormat = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.bookdetail
            .audioFormatDisplay(files: book.audioFiles)
        audioFileSizes = book.audioFiles.map { $0.size }
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
        shareURL = BookShareLink.url(bookId: bookId, instanceId: info.instanceId, remoteUrl: info.remoteUrl)
    }

    private func observeDownloadStatus(bookId: String) {
        bridge.bind(downloadService.observeBookStatus(bookId: BookId(value: bookId))) { [weak self] status in
            self?.applyDownloadStatus(status)
        }
    }

    private func applyNavAction(_ action: BookDetailNavAction) {
        switch Self.navReaction(to: action) {
        case .openDocument(let localPath):
            documentToOpen = ReaderDocument(localPath: localPath, title: title)
        case .showComingSoon:
            showComingSoon = true
        case .leaveDeletedBook:
            // Purge this device's copy before leaving, as Android does: the files are gone on the
            // server, so a download left behind would keep playing a book that no longer exists —
            // offline, indefinitely, with no way to reach it from the library.
            deleteDownload()
            didDeleteBook = true
        }
    }

    /// Pure: which reaction a nav action asks for.
    nonisolated static func navReaction(to action: BookDetailNavAction) -> BookDetailNavReaction {
        switch action.sealedType() {
        case .openDocumentViewer(let openType):
            return .openDocument(localPath: openType.value.localPath)
        case .showViewerComingSoon:
            return .showComingSoon
        case .bookDeleted:
            return .leaveDeletedBook
        }
    }

    /// Flatten the sealed `BookDownloadStatus` into the UI-facing download props.
    private func applyDownloadStatus(_ status: BookDownloadStatus) {
        latestDownloadStatus = status
        switch status.sealedType() {
        case .notDownloaded:
            downloadState = .notDownloaded
            downloadProgress = 0
            isDownloaded = false
        case .inProgress(let sType):
            let s = sType.value
            // A parked download is still "in progress" to the shared model, but nothing is moving.
            downloadState = isWaitingForWifi ? .waitingForWifi : .downloading
            downloadProgress = s.progress
            isDownloaded = false
        case .completed:
            downloadState = .completed
            downloadProgress = 1
            isDownloaded = true
        case .failed:
            downloadState = .failed
            isDownloaded = false
        case .paused(let sType):
            let s = sType.value
            downloadState = .partial
            downloadProgress = s.totalBytes > 0
                ? Float(s.downloadedBytes) / Float(s.totalBytes)
                : 0
            isDownloaded = false
        }
    }
}
