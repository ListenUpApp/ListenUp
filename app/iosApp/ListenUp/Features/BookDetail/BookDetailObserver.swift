import SwiftUI
import ListenupContract
@preconcurrency import Shared

/// Observes `BookDetailViewModel` — flattens the sealed `BookDetailUiState` into
/// flat `@Observable` properties, plus a download-status secondary flow. Thin over `FlowBridge`.
@Observable
@MainActor
final class BookDetailObserver {
    // MARK: - Flattened state

    private(set) var isLoading: Bool = true
    private(set) var error: String?
    /// Whether asking again could help (`AppError.isRetryable`): the error offers Retry only then.
    private(set) var errorIsRetryable = false
    private(set) var book: BookDetail?
    private(set) var subtitle: String?
    /// The book's series as paths — one line per series, "Cosmere › Mistborn #1", every part a link.
    private(set) var seriesPaths: [BookSeriesPathItem] = []
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
    /// Everyone credited on the book, for Cast & Credits' production group.
    private(set) var castMembers: [CastMember] = []
    private(set) var genres: [FacetChip] = []
    private(set) var tags: [FacetChip] = []
    private(set) var moods: [FacetChip] = []
    /// Where the reader is with the book and what follows from it, projected once per `Ready`.
    private(set) var reading = BookReadingState()

    // MARK: - Projected from `book`

    // Native value projections set once in `apply` `.ready`, never re-bridged in `body`.
    // BookDetail recomposes on every playback/download tick — reading these off the live
    // bridged object each render re-bridged its strings (and, for `audioFormat`, the whole
    // `audioFiles` collection) across the K/N boundary for a value that's static per book.
    private(set) var title: String = ""
    private(set) var authors: String = ""
    private(set) var duration: String = ""
    private(set) var durationMs: Int64 = 0
    /// Abridged or not, for the hero's stats line ("45 hr 30 min · 2010 · Unabridged").
    private(set) var isAbridged = false
    private(set) var asin: String?
    private(set) var publisher: String?
    private(set) var language: String?
    /// The hero's book-derived cover fields, projected so the hero never re-bridges the raw `BookDetail`.
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

    // MARK: - Held for review (admin inbox)

    /// Held for review: the page is triage-only (`BookDetailLayout.triage`). Never true for a member.
    private(set) var isHeld: Bool = false
    /// A release is in flight — Release shows its spinner.
    private(set) var isReleasingFromInbox: Bool = false
    /// Who can't see this book, for the Visibility section; nil for a member, a public book or a held one.
    private(set) var visibility: BookVisibilityModel?
    /// "Show to All Members" is in flight — its button reads "Showing to All Members…".
    private(set) var isRestoringToAllBooks = false
    var layout: BookDetailLayout { .forBook(isHeld: isHeld) }

    /// The last-match row while the book's last match can be undone (and you may undo it); nil otherwise.
    private(set) var lastMatch: LastMatchRowModel?
    /// How the row's Undo ended, said in the receipt capsule; the capsule sets it back to `.none` when dismissed.
    var lastMatchOutcome: MatchReceiptPhase = .none

    // MARK: - Documents

    private(set) var documents: [DocumentRow] = []
    /// The Details rows (`bookDetailRows`), recomputed when the book or its documents change.
    private(set) var detailRows: [BookDetailRowValue] = []
    /// The book's audio files, for the Files & Audio sheet.
    private(set) var audioFiles: [FilesAndAudioFile] = []
    private var lastReady: BookDetailUiStateReady?
    private var kotlinDocuments: [BookDocument] = []
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
    /// May edit this book's metadata, chapters and match (Edit metadata; admins always may).
    private(set) var canEditMetadata: Bool = false
    private(set) var showCollectionPicker: Bool = false
    private(set) var isAddingToCollection: Bool = false
    private(set) var collectionError: String?
    private(set) var allCollections: [CollectionRow] = []
    private(set) var startedAtMs: Int64?
    private(set) var isMarkingComplete: Bool = false
    private(set) var isDiscardingProgress: Bool = false
    private(set) var isRestarting: Bool = false
    /// A progress write is in flight: the More menu's three progress commands wait.
    private(set) var isChangingProgress = false
    /// The More menu's groups from the shared rule (`bookDetailMoreMenu`); `menu` drops Share without a link.
    private(set) var menuGroups: [BookDetailMenuSection] = []

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

    /// True while a play request for THIS book is in flight — drives the Resume/Play button's busy variant
    /// (spinner + "Preparing…" label, including to VoiceOver). Sourced from the shared player's debounced
    /// `preparingBookId` (Decision 11: a fast prepare doesn't flash the button). `@Observable` composes
    /// across the two objects, so this recomputes whenever `playerObserver.preparingBookId` changes.
    var isPlayPending: Bool {
        Self.isPlayPending(preparingBookId: playerObserver.preparingBookId, bookId: book?.idString)
    }

    /// Pure: a play is in flight for exactly `bookId`.
    nonisolated static func isPlayPending(preparingBookId: String?, bookId: String?) -> Bool {
        guard let bookId else { return false }
        return preparingBookId == bookId
    }

    // MARK: - Dependencies

    private let viewModel: BookDetailViewModel
    private let playerObserver: PlayerObserver
    private let downloadService: DownloadService
    private let bridge = FlowBridge()
    private var observingDownloadForBookId: String?

    // Latest raw shelf inputs; `myShelves` is recomputed when either updates.
    private var allShelves: [ShelfRow] = []
    private var shelfIdsContainingBook: Set<String> = []

    init(
        viewModel: BookDetailViewModel,
        playerObserver: PlayerObserver,
        downloadService: DownloadService
    ) {
        self.viewModel = viewModel
        bridge.own(viewModel)
        self.playerObserver = playerObserver
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
            self?.kotlinDocuments = docs
            self?.recomputeDetailRows()
        }
        bridge.bind(viewModel.openingDocumentIds) { [weak self] ids in
            self?.openingDocIds = Set(ids)
        }
        bridge.bind(viewModel.navActions) { [weak self] action in
            self?.applyNavAction(action)
        }
        bridge.bind(viewModel.lastMatch) { [weak self] in self?.lastMatch = $0.map(LastMatchMapping.row(from:)) }
        bridge.bind(viewModel.lastMatchEvents) { [weak self] in self?.lastMatchOutcome = LastMatchMapping.outcome($0) }
    }

    /// The Details rows for the last `Ready` and the latest documents, through the shared rule.
    private func recomputeDetailRows() {
        guard let lastReady else { return }
        detailRows = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.bookdetail
            .bookDetailRows(ready: lastReady, documents: kotlinDocuments)
            .map(BookDetailRowValue.init)
    }

    /// Fold the latest `myShelves` + containing-book membership into `[ShelfRow]`.
    private func recomputeShelfRows() {
        myShelves = allShelves.map {
            ShelfRow(id: $0.id, name: $0.name, containsBook: shelfIdsContainingBook.contains($0.id))
        }
    }

    // MARK: - Actions

    func loadBook(bookId: String) {
        viewModel.loadBook(bookId: bookId)
    }

    func seeWhatChanged() { viewModel.seeWhatChanged() }
    func closeWhatChanged() { viewModel.closeWhatChanged() }
    func undoLastMatch() { viewModel.undoLastMatch() }

    func play() {
        guard let book else { return }
        playerObserver.play(bookId: book.idString)
    }

    /// Plays this book from a chapter's start (`startMs`, book milliseconds).
    func play(fromChapterStartMs startMs: Int64) {
        guard let book else { return }
        playerObserver.play(bookId: book.idString, startAtMs: startMs)
    }

    /// Retry the server connection (a reconnect that runs catch-up) — the offline reason's and the error's Retry.
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

    // MARK: - Visibility (admin)

    /// Puts a stranded book back in All Books, so every member can see it again. No confirmation:
    /// it restores what was meant to be public.
    func restoreToAllBooks() { viewModel.restoreToAllBooks() }

    // MARK: - Progress

    func discardProgress() { viewModel.discardProgress() }

    /// Restart the book from the beginning, clearing current progress. `isRestarting` reflects the
    /// in-flight write; the shared VM resets progress/complete on success.
    func restartBook() { viewModel.restartBook() }

    /// Mark the book finished on the days the reader chose in the sheet. A start day they left alone
    /// claims nothing (the server keeps the start it knows); one they picked dates the read.
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
            seriesPaths = r.seriesPaths.map(BookSeriesPathItem.init)
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
            canEditMetadata = r.canEditMetadata
            showCollectionPicker = r.showCollectionPicker
            isAddingToCollection = r.isAddingToCollection
            collectionError = r.collectionError
            startedAtMs = r.startedAtMs
            isMarkingComplete = r.isMarkingComplete
            isDiscardingProgress = r.isDiscardingProgress
            isRestarting = r.isRestarting
            isChangingProgress = r.isChangingProgress
            menuGroups = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.bookdetail
                .bookDetailMoreMenu(ready: r).map(BookDetailMenuSection.init)
            isDeletingBook = r.isDeletingBook
            canPlay = r.canPlay
            canDownload = r.canDownload
            isHeld = r.isHeld
            isReleasingFromInbox = r.isReleasingFromInbox
            visibility = BookVisibilityModel.from(r.visibility)
            isRestoringToAllBooks = r.isRestoringToAllBooks
            reading = BookReadingState(r)
            lastReady = r
            recomputeDetailRows()
            if isWaitingForWifi != r.isWaitingForWifi {
                isWaitingForWifi = r.isWaitingForWifi
                latestDownloadStatus.map { applyDownloadStatus($0) }
            }
        case .error(let eType):
            let e = eType.value
            isLoading = false
            error = e.error.message
            errorIsRetryable = e.error.isRetryable
            // Nothing of the last book may linger behind an error: its menu and rows describe a book not on screen.
            menuGroups = []
            isChangingProgress = false
            lastReady = nil
            detailRows = []
        }
    }

    /// Projects the bridged `BookDetail` to native values once, off the `body` diff path.
    /// Cover fields, scalars, and the audio-format summary are all snapshotted
    /// here so the detail screen never re-bridges the Kotlin object on a playback/download tick.
    private func applyBook(_ book: BookDetail) {
        self.book = book
        title = book.title
        authors = book.authorNames
        duration = book.formatDuration()
        durationMs = book.duration
        isAbridged = book.abridged
        asin = book.asin
        publisher = book.publisher
        // Show the real language name ("English"), not the stored code ("en").
        language = book.language.map(LanguageName.display)
        header = BookDetailHeaderModel(
            coverBookId: book.idString,
            coverPath: book.coverPath,
            coverHash: book.coverHash
        )
        audioFormat = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.bookdetail
            .audioFormatDisplay(files: book.audioFiles)
        audioFileSizes = book.audioFiles.map { $0.size }
        audioFiles = book.audioFiles.map {
            FilesAndAudioFile(id: $0.id, name: documentBasename($0.filename), durationMs: $0.duration,
                              sizeBytes: $0.size)
        }
        heroAuthors = book.authors.map { CastMember($0) }
        heroNarrators = book.narrators.map { CastMember($0) }
        castMembers = book.allContributors.map { CastMember($0) }
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
