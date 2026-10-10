package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.BookVisibilityRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.client.presentation.match.UndoMatch
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the Book Detail screen.
 *
 * [state] is derived, never written: the requested book id, the book's [BookLoad] from Room, the account-wide
 * [BookDetailAmbient] facts and the reader's [BookDetailOverlay] combine through the pure [bookDetailUiState]
 * into `stateIn(WhileSubscribed(5_000))`. Actions only write the overlay or call repositories. Room is observed
 * only while a screen watches, and switching books cancels the previous book's observers through `flatMapLatest`.
 * The shape is [com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel]'s.
 */
@Suppress("LongParameterList") // DI constructor: each param is a distinct domain responsibility.
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModel(
    private val bookRepository: BookRepository,
    tagRepository: TagRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
    userRepository: UserRepository,
    permissionsRepository: PermissionsRepository,
    shelfRepository: ShelfRepository,
    private val collectionRepository: CollectionRepository,
    private val addBooksToShelfUseCase: AddBooksToShelfUseCase,
    private val createShelfUseCase: CreateShelfUseCase,
    private val errorBus: ErrorBus,
    private val bookAvailability: BookAvailability,
    private val serverReachability: ServerReachability,
    private val documentRepository: DocumentRepository,
    private val inboxRepository: InboxRepository,
    private val bookVisibilityRepository: BookVisibilityRepository,
    private val bookEditRepository: BookEditRepository,
    seriesRepository: SeriesRepository,
    undoMatch: UndoMatch,
    userProfileRepository: UserProfileRepository,
) : ViewModel() {
    /**
     * The requested book id. Writing it is the single entry point for switching books; every per-book flow
     * below `flatMapLatest`s on it, so book-switch cancellation is automatic.
     */
    private val bookIdFlow = MutableStateFlow<String?>(null)

    /** The reader's transient state on the requested book. Only actions and [BookDetailOverlay.retiredBy] write it. */
    private val overlay = MutableStateFlow(BookDetailOverlay(bookId = null))

    private val _navActions = Channel<BookDetailNavAction>(Channel.BUFFERED)

    /** One-shot navigation and side-effect events — collect once at the screen entry point. */
    val navActions: Flow<BookDetailNavAction> = _navActions.receiveAsFlow()

    private val lastMatchRow =
        LastMatchRow(
            bookIds = bookIdFlow.filterNotNull(),
            scope = viewModelScope,
            bookRepository = bookRepository,
            permissionsRepository = permissionsRepository,
            userRepository = userRepository,
            userProfileRepository = userProfileRepository,
            undoMatch = undoMatch,
            errorBus = errorBus,
        )

    /**
     * "Details matched <relative time> · See what changed · Undo last match", from Room: present only while the book
     * is at the revision its last match left it at, and only for someone with Edit metadata (the server's gate on
     * Undo). Null otherwise.
     */
    val lastMatch: StateFlow<LastMatchUi?> = lastMatchRow.state

    /** How "Undo last match" ended — said once, as the receipt says it. */
    val lastMatchEvents: Flow<LastMatchEvent> = lastMatchRow.events

    // A StateFlow so a screen coming back sees the last facts at once (no flash of isAdmin = false); it stops
    // with [state], which owns the five-second grace.
    private val ambient: StateFlow<BookDetailAmbient> =
        combine(
            flow = userRepository.observeIsAdmin(),
            flow2 = permissionsRepository.observeCan(Permission.EDIT_METADATA),
            flow3 = seriesRepository.observeHierarchy(),
            flow4 = tagRepository.observeAll(),
        ) { isAdmin, canEditMetadata, hierarchy, allTags ->
            BookDetailAmbient(isAdmin, canEditMetadata, hierarchy, allTags)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), BookDetailAmbient())

    // A StateFlow so a screen coming back to the same book shows it at once instead of Loading; it stops with
    // [state], which owns the five-second grace.
    private val bookLoad: StateFlow<BookLoad?> =
        bookIdFlow
            .filterNotNull()
            .flatMapLatest { id -> observeBook(id) }
            .onEach { load -> overlay.update { it.retiredBy(load) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val state: StateFlow<BookDetailUiState> =
        combine(
            flow = bookIdFlow,
            flow2 = bookLoad,
            flow3 = ambient,
            flow4 = overlay,
        ) { requested, load, ambientFacts, own ->
            bookDetailUiState(requested, load, ambientFacts, own)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState.Loading)

    /**
     * User's shelves for the shelf picker sheet.
     */
    val myShelves: StateFlow<List<Shelf>> =
        userRepository
            .observeCurrentUser()
            .flatMapLatest { user ->
                if (user != null) {
                    shelfRepository.observeMyShelves(user.id.value)
                } else {
                    flowOf(emptyList())
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Non-system collections an admin can file this book into (system collections are implicit). */
    val collections: StateFlow<List<Collection>> =
        collectionRepository
            .observeCollections()
            .map { all -> all.filterNot { it.isSystem } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The caller's own shelves that currently contain the loaded book, alphabetical.
     * Lets the shelf picker mark already-added shelves and the detail show shelf badges.
     * Reactive + offline; switches automatically when the displayed book changes.
     */
    val shelvesContainingBook: StateFlow<List<Shelf>> =
        bookIdFlow
            .filterNotNull()
            .flatMapLatest { id -> shelfRepository.observeShelvesContainingBook(BookId(id)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Supplementary documents for the loaded book, ordered by index ascending.
     *
     * Reactive — switches automatically when [loadBook] is called. Empty until the
     * book's documents have been synced to the local Room store.
     */
    val documents: StateFlow<List<BookDocument>> =
        bookIdFlow
            .filterNotNull()
            .flatMapLatest { id -> documentRepository.observeDocuments(BookId(id)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Document ids whose bytes are currently being fetched by [onOpenDocument].
     *
     * A document is added before [DocumentRepository.ensureLocal] and removed once it
     * resolves — on success, failure, or cancellation. Lets the UI show a per-row
     * spinner; the open flow itself is unchanged.
     */
    val openingDocumentIds: StateFlow<Set<String>>
        field = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Room's word on [bookId]: one-shot reads for the chapters and the saved position (PR 1 makes both live),
     * then the row, its availability, the held set and its visibility, live.
     */
    private fun observeBook(bookId: String): Flow<BookLoad> =
        flow {
            val chapters = bookRepository.getChapters(bookId)
            val position: PlaybackPosition? =
                when (val r = playbackPositionRepository.get(BookId(bookId))) {
                    is AppResult.Success -> {
                        r.data
                    }

                    is AppResult.Failure -> {
                        logger.warn { "BookDetailViewModel: get($bookId) failed: ${r.error.message}" }
                        null
                    }
                }
            emitAll(
                combine(
                    flow = bookRepository.observeBookDetail(bookId),
                    flow2 = bookAvailability.observe(BookId(bookId)),
                    flow3 = inboxRepository.observeHeldBookIds(),
                    // Null on a member's device; live for an admin (share/membership/hold/roster changes).
                    flow4 = bookVisibilityRepository.observeBookVisibility(BookId(bookId)),
                ) { detail, availability, heldIds, visibility ->
                    if (detail == null) {
                        BookLoad.Missing(bookId)
                    } else {
                        BookLoad.Loaded(
                            bookId = bookId,
                            snapshot =
                                BookSnapshot(
                                    detail = detail,
                                    chapters = chapters,
                                    position = position,
                                    availability = availability,
                                    isHeld = BookId(bookId) in heldIds,
                                    visibility = visibility,
                                ),
                        )
                    }
                },
            )
        }

    /** The page on screen, or null while loading or failed — the book an action acts on. */
    private fun shownReady(): BookDetailUiState.Ready? = state.value as? BookDetailUiState.Ready

    /** [bookId]'s overlay, or null once the reader has moved to another book. */
    private fun overlayOf(bookId: String): BookDetailOverlay? = overlay.value.takeIf { it.bookId == bookId }

    /** Writes [bookId]'s overlay; a no-op once the reader has moved on, so a late answer cannot reach another book. */
    private fun updateOverlay(
        bookId: String,
        transform: (BookDetailOverlay) -> BookDetailOverlay,
    ) {
        overlay.update { current -> if (current.bookId == bookId) transform(current) else current }
    }

    /** Writes the overlay of the book on screen; nothing happens while no book is shown. */
    private fun updateShownOverlay(transform: (BookDetailOverlay) -> BookDetailOverlay) {
        val bookId = shownReady()?.book?.id?.value ?: return
        updateOverlay(bookId, transform)
    }

    /**
     * Switch the view model to observe [bookId]. A different book starts a fresh overlay; the same book keeps
     * whatever the reader has open.
     */
    fun loadBook(bookId: String) {
        bookIdFlow.value = bookId
        if (overlay.value.bookId != bookId) overlay.value = BookDetailOverlay(bookId = bookId)
    }

    /** Opens See what changed for the last match: every change it made, with where each came from. */
    fun seeWhatChanged() {
        lastMatchRow.seeWhatChanged()
    }

    /** Closes See what changed. */
    fun closeWhatChanged() {
        lastMatchRow.closeWhatChanged()
    }

    /**
     * Undoes the book's last match through the shared [UndoMatch]. Success retires the row (the restored book
     * reaches Room) and says so on [lastMatchEvents]; a match the server calls too late retires it with
     * [LastMatchEvent.Expired]; any other failure goes to the error bus and stays on the row as its `undoError`.
     */
    fun undoLastMatch() {
        lastMatchRow.undo()
    }

    /**
     * Mark the current book as complete with optional date overrides.
     *
     * @param startedAt The start day the reader picked, in epoch milliseconds; null when they left it
     * @param finishedAt Optional finish date in epoch milliseconds
     */
    fun markComplete(
        startedAt: Long? = null,
        finishedAt: Long? = null,
    ) {
        val bookId = shownReady()?.book?.id?.value ?: return
        viewModelScope.launch {
            updateOverlay(bookId) { it.copy(isMarkingComplete = true) }
            when (playbackPositionRepository.markComplete(BookId(bookId), startedAt, finishedAt)) {
                is AppResult.Success -> {
                    updateOverlay(
                        bookId,
                    ) { it.copy(isMarkingComplete = false, progressOverride = ProgressOverride.Completed) }
                    logger.info { "Marked book $bookId as complete" }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId) { it.copy(isMarkingComplete = false) }
                    logger.error { "Failed to mark book $bookId as complete" }
                }
            }
        }
    }

    /**
     * Discard progress for the current book (start over / DNF).
     */
    fun discardProgress() {
        val bookId = shownReady()?.book?.id?.value ?: return
        viewModelScope.launch {
            updateOverlay(bookId) { it.copy(isDiscardingProgress = true) }
            when (playbackPositionRepository.discardProgress(BookId(bookId))) {
                is AppResult.Success -> {
                    updateOverlay(
                        bookId,
                    ) { it.copy(isDiscardingProgress = false, progressOverride = ProgressOverride.Discarded) }
                    logger.info { "Discarded progress for book $bookId" }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId) { it.copy(isDiscardingProgress = false) }
                    logger.error { "Failed to discard progress for book $bookId" }
                }
            }
        }
    }

    /**
     * Restart the current book from the beginning.
     */
    fun restartBook() {
        val bookId = shownReady()?.book?.id?.value ?: return
        viewModelScope.launch {
            updateOverlay(bookId) { it.copy(isRestarting = true) }
            when (playbackPositionRepository.restartBook(BookId(bookId))) {
                is AppResult.Success -> {
                    updateOverlay(
                        bookId,
                    ) { it.copy(isRestarting = false, progressOverride = ProgressOverride.Restarted) }
                    logger.info { "Restarted book $bookId" }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId) { it.copy(isRestarting = false) }
                    logger.error { "Failed to restart book $bookId" }
                }
            }
        }
    }

    /**
     * Force a fresh server-reachability check, backing the offline banner's "Retry"
     * action. Tears down and re-opens the sync firehose so the reachability indicator
     * recovers without waiting on the automatic backoff loop.
     */
    fun retryConnection() {
        viewModelScope.launch {
            serverReachability.retry()
        }
    }

    /**
     * Add the current book to an existing shelf.
     */
    fun addBookToShelf(shelfId: String) {
        val bookId = shownReady()?.book?.id?.value ?: return
        viewModelScope.launch {
            updateOverlay(bookId) { it.copy(isAddingToShelf = true) }
            when (val result = addBooksToShelfUseCase(ShelfId(shelfId), listOf(BookId(bookId)))) {
                is AppResult.Success -> {
                    updateOverlay(bookId) { it.copy(isAddingToShelf = false, showShelfPicker = false) }
                    logger.info { "Added book $bookId to shelf $shelfId" }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId) { it.copy(isAddingToShelf = false, shelfError = result.message) }
                    logger.error { "Failed to add book $bookId to shelf $shelfId: ${result.message}" }
                }
            }
        }
    }

    /**
     * Create a new shelf and add the current book to it.
     */
    fun createShelfAndAddBook(name: String) {
        val bookId = shownReady()?.book?.id?.value ?: return
        viewModelScope.launch {
            updateOverlay(bookId) { it.copy(isAddingToShelf = true) }
            when (val result = createShelfUseCase(name, null)) {
                is AppResult.Success -> {
                    val shelf = result.data
                    when (val addResult = addBooksToShelfUseCase(shelf.id, listOf(BookId(bookId)))) {
                        is AppResult.Success -> {
                            updateOverlay(bookId) { it.copy(isAddingToShelf = false, showShelfPicker = false) }
                            logger.info { "Created shelf '${shelf.name}' and added book $bookId" }
                        }

                        is AppResult.Failure -> {
                            updateOverlay(bookId) { it.copy(isAddingToShelf = false, shelfError = addResult.message) }
                            logger.error { "Created shelf but failed to add book $bookId: ${addResult.message}" }
                        }
                    }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId) { it.copy(isAddingToShelf = false, shelfError = result.message) }
                    logger.error { "Failed to create shelf '$name': ${result.message}" }
                }
            }
        }
    }

    fun clearShelfError() {
        updateShownOverlay { it.copy(shelfError = null) }
    }

    fun clearCollectionError() {
        updateShownOverlay { it.copy(collectionError = null) }
    }

    fun showShelfPicker() {
        updateShownOverlay { it.copy(showShelfPicker = true) }
    }

    fun hideShelfPicker() {
        updateShownOverlay { it.copy(showShelfPicker = false) }
    }

    fun showCollectionPicker() {
        updateShownOverlay { it.copy(showCollectionPicker = true) }
    }

    fun hideCollectionPicker() {
        updateShownOverlay { it.copy(showCollectionPicker = false) }
    }

    /** Add this book to [collectionId] (additive — never affects the book's All Books membership). */
    fun addBookToCollection(collectionId: String) {
        val bookId = shownReady()?.book?.id?.value ?: return
        viewModelScope.launch {
            updateOverlay(bookId) { it.copy(isAddingToCollection = true) }
            when (val result = collectionRepository.addBook(collectionId, bookId)) {
                is AppResult.Success -> {
                    updateOverlay(bookId) { it.copy(isAddingToCollection = false, showCollectionPicker = false) }
                    logger.info { "Added book $bookId to collection $collectionId" }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId) { it.copy(isAddingToCollection = false, collectionError = result.message) }
                    logger.error { "Failed to add book $bookId to collection $collectionId: ${result.message}" }
                }
            }
        }
    }

    /**
     * Create a new collection and add the current book to it.
     *
     * Admin-only: the Add-to-Collection picker is already admin-gated, so this create
     * affordance inherits that gating. The new collection is scoped to the loaded book's
     * library.
     */
    fun createCollectionAndAddBook(name: String) {
        val book = shownReady()?.book ?: return
        val bookId = book.id.value
        viewModelScope.launch {
            updateOverlay(bookId) { it.copy(isAddingToCollection = true) }
            when (val result = collectionRepository.create(book.libraryId.value, name)) {
                is AppResult.Success -> {
                    val collection = result.data
                    when (val addResult = collectionRepository.addBook(collection.id, bookId)) {
                        is AppResult.Success -> {
                            updateOverlay(
                                bookId,
                            ) { it.copy(isAddingToCollection = false, showCollectionPicker = false) }
                            logger.info { "Created collection '${collection.name}' and added book $bookId" }
                        }

                        is AppResult.Failure -> {
                            updateOverlay(
                                bookId,
                            ) { it.copy(isAddingToCollection = false, collectionError = addResult.message) }
                            logger.error { "Created collection but failed to add book $bookId: ${addResult.message}" }
                        }
                    }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId) { it.copy(isAddingToCollection = false, collectionError = result.message) }
                    logger.error { "Failed to create collection '$name': ${result.message}" }
                }
            }
        }
    }

    /**
     * **Permanently deletes this book — its folder and every file in it — from the server.**
     * Admin-only; the menu entry that reaches here is gated on [BookDetailUiState.Ready.isAdmin],
     * and the server gates it again.
     *
     * On success the screen is done: [BookDetailNavAction.BookDeleted] tells the host to leave,
     * because staying would sit on a book whose row is about to vanish underneath it when the
     * tombstone syncs. On failure nothing was deleted — the typed refusal goes to [errorBus] for
     * the global snackbar, and [BookDetailUiState.Ready.deleteError] carries it back to the
     * confirm dialog so the reason is legible where the decision was made.
     */
    fun deleteBook() {
        val bookId = shownReady()?.book?.id ?: return
        viewModelScope.launch {
            updateOverlay(bookId.value) { it.copy(isDeletingBook = true, deleteError = null) }
            when (val result = bookRepository.deleteBook(bookId)) {
                is AppResult.Success -> {
                    updateOverlay(bookId.value) { it.copy(isDeletingBook = false) }
                    logger.info { "Deleted book ${bookId.value} and its folder" }
                    _navActions.trySend(BookDetailNavAction.BookDeleted)
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId.value) { it.copy(isDeletingBook = false, deleteError = result.error) }
                    errorBus.emit(result.error)
                    logger.error { "Failed to delete book ${bookId.value}: ${result.error.code}" }
                }
            }
        }
    }

    /** Clears the inline delete refusal so the confirm dialog can be dismissed or retried cleanly. */
    fun clearDeleteError() {
        updateShownOverlay { it.copy(deleteError = null) }
    }

    /**
     * Releases this held book to everyone — `releaseBooks` with an empty target list moves it into
     * ALL_BOOKS. The UI confirms first ("Release to everyone?"); this is what its Release button calls.
     *
     * Only meaningful when [BookDetailUiState.Ready.isHeld], which is never true on a member's device.
     * On success there is nothing to emit: the INBOX membership leaves Room at once (the repository
     * writes the release through), [BookDetailUiState.Ready.isHeld] turns false, and the held section
     * goes with it. A refusal goes to [errorBus] and the book stays held.
     */
    fun releaseFromInbox() {
        val ready = shownReady() ?: return
        val book = ready.book
        val bookId = book.id.value
        // The overlay, not `state`: a second tap in the same frame must see the first, before `state` recombines.
        if (!ready.isHeld || overlayOf(bookId)?.isReleasingFromInbox != false) return
        updateOverlay(bookId) { it.copy(isReleasingFromInbox = true) }
        viewModelScope.launch {
            when (val result = inboxRepository.releaseBooks(book.libraryId.value, mapOf(bookId to emptyList()))) {
                is AppResult.Success -> {
                    updateOverlay(bookId) { it.copy(isReleasingFromInbox = false) }
                    logger.info { "Released $bookId from the inbox" }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId) { it.copy(isReleasingFromInbox = false) }
                    errorBus.emit(result.error)
                    logger.error { "Failed to release $bookId from the inbox: ${result.error.code}" }
                }
            }
        }
    }

    /**
     * Puts a **stranded** book — in no collection at all, so hidden from every member — back into
     * All Books ("Show to all members"; spec §7: no confirmation, it restores what was meant to be
     * public). Admin-only by construction: only an admin's device ever computes a visibility.
     *
     * Sends an empty collection set through the book-edit outbox (offline-first). Its local apply
     * runs the same system-membership reconcile as the server, so the book is re-homed into All Books
     * in Room at once, even offline. That moves [BookDetailUiState.Ready.visibility] to Public and
     * ends [BookDetailUiState.Ready.isRestoringToAllBooks]; the server's echo then confirms it. Does
     * nothing unless the book is stranded, not held, and no restore is already waiting.
     */
    fun restoreToAllBooks() {
        val ready = shownReady() ?: return
        val bookId = ready.book.id
        // The overlay, not `state`: a second tap in the same frame must see the first, before `state` recombines.
        if (ready.isHeld || ready.visibility !is BookVisibility.Stranded ||
            overlayOf(bookId.value)?.isRestoringToAllBooks != false
        ) {
            return
        }
        updateOverlay(bookId.value) { it.copy(isRestoringToAllBooks = true) }
        viewModelScope.launch {
            when (val result = bookEditRepository.setBookCollections(bookId, emptyList())) {
                is AppResult.Success -> {
                    logger.info { "Queued stranded book ${bookId.value} to return to All Books" }
                }

                is AppResult.Failure -> {
                    updateOverlay(bookId.value) { it.copy(isRestoringToAllBooks = false) }
                    errorBus.emit(result.error)
                    logger.error { "Failed to queue ${bookId.value} back to All Books: ${result.error.code}" }
                }
            }
        }
    }

    /**
     * Handle a tap on a supplementary document row.
     *
     * For PDF documents: downloads (if not already cached) then emits
     * [BookDetailNavAction.OpenDocumentViewer] with the local file path so the
     * platform-specific viewer screen can render it.
     *
     * For non-PDF formats: emits [BookDetailNavAction.ShowViewerComingSoon] — the
     * path is NOT resolved and [DocumentRepository.ensureLocal] is NOT called.
     *
     * @param docId [BookDocument.id] of the tapped document.
     */
    fun onOpenDocument(docId: String) {
        val bookId = shownReady()?.book?.id?.value ?: return
        val doc = documents.value.find { it.id == docId } ?: return
        if (doc.format != "pdf") {
            _navActions.trySend(BookDetailNavAction.ShowViewerComingSoon)
            return
        }
        viewModelScope.launch {
            openingDocumentIds.update { it + docId }
            try {
                when (val result = documentRepository.ensureLocal(BookId(bookId), docId)) {
                    is AppResult.Success -> {
                        _navActions.trySend(BookDetailNavAction.OpenDocumentViewer(result.data))
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        logger.error { "Failed to open document $docId for book $bookId: ${result.error.message}" }
                    }
                }
            } finally {
                openingDocumentIds.update { it - docId }
            }
        }
    }
}
