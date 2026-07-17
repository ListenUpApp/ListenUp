package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.core.Failure
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the Book Detail screen.
 *
 * Uses reactive flows with [flatMapLatest] to automatically switch observers
 * when navigating between books, eliminating manual Job cancellation.
 */
@Suppress("LongParameterList") // DI constructor: each param is a distinct domain responsibility.
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModel(
    private val bookRepository: BookRepository,
    private val tagRepository: TagRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val userRepository: UserRepository,
    private val shelfRepository: ShelfRepository,
    private val collectionRepository: CollectionRepository,
    private val addBooksToShelfUseCase: AddBooksToShelfUseCase,
    private val createShelfUseCase: CreateShelfUseCase,
    private val errorBus: ErrorBus,
    private val bookAvailability: BookAvailability,
    private val serverReachability: ServerReachability,
    private val documentRepository: DocumentRepository,
) : ViewModel() {
    val state: StateFlow<BookDetailUiState>
        field = MutableStateFlow<BookDetailUiState>(BookDetailUiState.Loading)

    private val _navActions = Channel<BookDetailNavAction>(Channel.BUFFERED)

    /** One-shot navigation and side-effect events — collect once at the screen entry point. */
    val navActions: Flow<BookDetailNavAction> = _navActions.receiveAsFlow()

    /**
     * The currently requested book id. Writing to this flow is the single entry
     * point for switching books — the main-load flatMapLatest subscribes here
     * directly, so book-switch cancellation is automatic.
     */
    private val bookIdFlow = MutableStateFlow<String?>(null)

    // Mirrors of book-INDEPENDENT flow-fed fields (admin status, all-tags). Updated
    // inside the init collectors and read at [loadBookFlow] to seed the Ready state
    // with the latest known values, since those collectors may have emitted while
    // state was Loading (and been no-op'd by [updateReady]).
    //
    // Per-book genres/tags now flow through [BookDetail] directly via
    // [BookRepository.observeBookDetail], so no mirror is needed for them.
    private var latestIsAdmin: Boolean = false
    private var latestAllTags: List<Tag> = emptyList()

    init {
        // Observe admin status
        viewModelScope.launch {
            userRepository.observeIsAdmin().collect { isAdmin ->
                latestIsAdmin = isAdmin
                updateReady { it.copy(isAdmin = isAdmin) }
            }
        }

        // All tags observer (doesn't depend on current book - for tag picker)
        viewModelScope.launch {
            tagRepository
                .observeAll()
                .collect { allTags ->
                    latestAllTags = allTags
                    updateReady { it.copy(allTags = allTags) }
                }
        }

        // Main load flow: bookIdFlow changes drive loadBookFlow, which emits
        // Loading then maps the BookDetail flow to Ready (or Error if the row
        // is absent). flatMapLatest cancels the previous observer on switch.
        viewModelScope.launch {
            bookIdFlow
                .filterNotNull()
                .flatMapLatest { id -> loadBookFlow(id) }
                .collect { built -> state.value = built.preservingTransientOverlays(state.value) }
        }
    }

    /**
     * Carry user-driven transient overlay state (open pickers, in-flight action flags, inline
     * errors) forward when [loadBookFlow] rebuilds [BookDetailUiState.Ready] from Room/availability.
     *
     * Those flows re-emit on every table invalidation (an SSE firehose frame the sync engine
     * applies, a download progress tick, a reachability flip) — each rebuild resets these overlay
     * fields to their defaults. Without preserving them, any background emission would silently close
     * an open shelf/collection picker (and its create dialog) mid-interaction — the create-from-book-
     * detail bug. Only carried between two [Ready]s for the *same* load; a book switch emits [Loading]
     * first (breaking the chain), so overlays never leak across books.
     *
     * NOTE: when adding a new user-transient field to [Ready], add it here too.
     */
    private fun BookDetailUiState.preservingTransientOverlays(previous: BookDetailUiState): BookDetailUiState {
        if (this !is BookDetailUiState.Ready || previous !is BookDetailUiState.Ready) return this
        return copy(
            isMarkingComplete = previous.isMarkingComplete,
            isDiscardingProgress = previous.isDiscardingProgress,
            isRestarting = previous.isRestarting,
            isLoadingTags = previous.isLoadingTags,
            showTagPicker = previous.showTagPicker,
            showShelfPicker = previous.showShelfPicker,
            isAddingToShelf = previous.isAddingToShelf,
            shelfError = previous.shelfError,
            showCollectionPicker = previous.showCollectionPicker,
            isAddingToCollection = previous.isAddingToCollection,
            collectionError = previous.collectionError,
        )
    }

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
     * Apply [transform] to state only if it is currently [BookDetailUiState.Ready].
     * No-ops when state is [BookDetailUiState.Loading] or [BookDetailUiState.Error].
     */
    private fun updateReady(transform: (BookDetailUiState.Ready) -> BookDetailUiState.Ready) {
        state.update { current ->
            if (current is BookDetailUiState.Ready) transform(current) else current
        }
    }

    /**
     * Switch the view model to observe [bookId].
     *
     * Pushes into [bookIdFlow]; the main-load `flatMapLatest` in init collects
     * `loadBookFlow(id)`, which subscribes to [BookRepository.observeBookDetail]
     * and maps each emission into a [BookDetailUiState.Ready]. Switching books
     * cancels the in-flight observer automatically.
     */
    fun loadBook(bookId: String) {
        bookIdFlow.value = bookId
    }

    /**
     * Pure flow that drives the main book-load pipeline for [bookId].
     *
     * Emits [BookDetailUiState.Loading] immediately, performs one-shot reads
     * for chapters and the saved playback position, then maps the
     * [BookRepository.observeBookDetail] flow into a stream of
     * [BookDetailUiState.Ready] emissions. Genres and tags travel inside
     * [BookDetail] itself, so a single observer drives the whole detail
     * surface — no per-book genre or tag-for-book combine is needed.
     *
     * The caller ([init] block) collects into [state] via `flatMapLatest`, so
     * switching books cancels the in-flight observer automatically.
     */
    private fun loadBookFlow(bookId: String): Flow<BookDetailUiState> =
        flow {
            emit(BookDetailUiState.Loading)

            // One-shot reads — these don't reactively update during a single
            // book viewing. Domain chapters are mapped to UI models inside
            // buildReady, where the playback position is known and the
            // current-chapter highlight can be resolved.
            val domainChapters = bookRepository.getChapters(bookId)
            val position =
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
                    bookRepository.observeBookDetail(bookId),
                    bookAvailability.observe(BookId(bookId)),
                ) { detail, availability ->
                    if (detail == null) {
                        BookDetailUiState.Error(BookError.NotFound())
                    } else {
                        buildReady(detail, domainChapters, position).copy(
                            downloadStatus = availability.downloadStatus,
                            isPlaybackAvailable = availability.isPlaybackAvailable,
                            canPlay = availability.canPlay,
                            canDownload = availability.canDownload,
                            showServerWarning = availability.showServerWarning,
                            isWaitingForWifi = availability.isWaitingForWifi,
                        )
                    }
                },
            )
        }

    /**
     * Map a [BookDetail] emission plus the one-shot [chapters]/[position] reads
     * into a [BookDetailUiState.Ready]. Extracted from [loadBookFlow] to keep
     * the flow body's cognitive complexity in check.
     */
    private fun buildReady(
        detail: BookDetail,
        domainChapters: List<Chapter>,
        position: PlaybackPosition?,
    ): BookDetailUiState.Ready {
        // Filter out subtitles that just restate a series the book belongs to (name, or name + book
        // number) — checked against every membership now that a book can be in several series.
        val displaySubtitle =
            detail.subtitle?.takeUnless { subtitle ->
                detail.series.any { isSubtitleRedundant(subtitle, it.seriesName, it.sequence) }
            }

        val progress =
            if (position != null && detail.duration > 0) {
                (position.positionMs.toFloat() / detail.duration).coerceIn(0f, 1f)
            } else {
                null
            }

        // Authoritative completion flag from the saved position
        val isComplete = position?.isFinished ?: false

        val hasMeaningfulProgress = progress != null && progress > 0f && !isComplete

        // Resolve the current-chapter highlight only once the position is known.
        // An un-started book (no meaningful progress) highlights nothing.
        val currentIdx =
            if (hasMeaningfulProgress) {
                currentChapterIndex(domainChapters.map { it.startTime }, position?.positionMs ?: 0L)
            } else {
                null
            }

        val chapters =
            domainChapters.mapIndexed { index, domainChapter ->
                ChapterUiModel(
                    id = domainChapter.id,
                    title = domainChapter.title,
                    duration = domainChapter.formatDuration(),
                    imageUrl = null, // Placeholder
                    isCurrent = index == currentIdx,
                )
            }

        val timeRemaining =
            if (hasMeaningfulProgress) {
                val remainingMs = detail.duration - (position?.positionMs ?: 0L)
                DurationFormatter.timeLeft(remainingMs.milliseconds)
            } else {
                null
            }

        return BookDetailUiState.Ready(
            book = detail,
            isAdmin = latestIsAdmin,
            allTags = latestAllTags,
            isComplete = isComplete,
            startedAtMs = position?.startedAtMs,
            subtitle = displaySubtitle,
            series = detail.fullSeriesTitle,
            descriptionText = detail.description ?: "",
            narrators = detail.narratorNames,
            year = detail.publishYear,
            rating = detail.rating,
            chapters = chapters,
            progress = if (hasMeaningfulProgress) progress else null,
            timeRemainingFormatted = timeRemaining,
            addedAt = detail.addedAt.epochMillis,
            hasScanWarning = detail.hasScanWarning,
            genres = detail.genres,
            tags = detail.tags,
            moods = detail.moods,
        )
    }

    /**
     * Show the tag picker sheet.
     */
    fun showTagPicker() {
        updateReady { it.copy(showTagPicker = true) }
    }

    /**
     * Hide the tag picker sheet.
     */
    fun hideTagPicker() {
        updateReady { it.copy(showTagPicker = false) }
    }

    /**
     * Add a tag to the current book.
     *
     * @param slug The tag slug to add
     */
    fun addTag(slug: String) {
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
        viewModelScope.launch {
            when (val result = tagRepository.addTagToBook(bookId, slug)) {
                is AppResult.Success -> { /* Observer will update UI automatically */ }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to add tag '$slug' to book $bookId: ${result.error.message}" }
                }
            }
        }
    }

    /**
     * Remove a tag from the current book.
     *
     * @param slug The tag slug to remove
     */
    fun removeTag(slug: String) {
        val ready = state.value as? BookDetailUiState.Ready ?: return
        val bookId = ready.book.id.value
        val tag = ready.tags.find { it.slug == slug } ?: return
        viewModelScope.launch {
            when (val result = tagRepository.removeTagFromBook(bookId, slug, tag.id)) {
                is AppResult.Success -> { /* Observer will update UI automatically */ }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to remove tag '$slug' from book $bookId: ${result.error.message}" }
                }
            }
        }
    }

    /**
     * Add a new tag to the current book.
     *
     * The raw input will be normalized to a slug by the server.
     * If the tag doesn't exist, it will be created.
     *
     * @param rawInput The tag text to add (will be normalized)
     */
    fun addNewTag(rawInput: String) {
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
        viewModelScope.launch {
            when (val result = tagRepository.addTagToBook(bookId, rawInput)) {
                is AppResult.Success -> {
                    // Observer will update UI automatically
                    hideTagPicker()
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to add tag '$rawInput' to book $bookId: ${result.error.message}" }
                }
            }
        }
    }

    /**
     * Mark the current book as complete with optional date overrides.
     *
     * @param startedAt Optional start date in epoch milliseconds
     * @param finishedAt Optional finish date in epoch milliseconds
     */
    fun markComplete(
        startedAt: Long? = null,
        finishedAt: Long? = null,
    ) {
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
        viewModelScope.launch {
            updateReady { it.copy(isMarkingComplete = true) }
            when (playbackPositionRepository.markComplete(BookId(bookId), startedAt, finishedAt)) {
                is AppResult.Success -> {
                    updateReady { it.copy(isMarkingComplete = false, isComplete = true) }
                    logger.info { "Marked book $bookId as complete" }
                }

                is AppResult.Failure -> {
                    updateReady { it.copy(isMarkingComplete = false) }
                    logger.error { "Failed to mark book $bookId as complete" }
                }
            }
        }
    }

    /**
     * Discard progress for the current book (start over / DNF).
     */
    fun discardProgress() {
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
        viewModelScope.launch {
            updateReady { it.copy(isDiscardingProgress = true) }
            when (playbackPositionRepository.discardProgress(BookId(bookId))) {
                is AppResult.Success -> {
                    updateReady {
                        it.copy(
                            isDiscardingProgress = false,
                            isComplete = false,
                            progress = null,
                            timeRemainingFormatted = null,
                        )
                    }
                    logger.info { "Discarded progress for book $bookId" }
                }

                is AppResult.Failure -> {
                    updateReady { it.copy(isDiscardingProgress = false) }
                    logger.error { "Failed to discard progress for book $bookId" }
                }
            }
        }
    }

    /**
     * Restart the current book from the beginning.
     */
    fun restartBook() {
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
        viewModelScope.launch {
            updateReady { it.copy(isRestarting = true) }
            when (playbackPositionRepository.restartBook(BookId(bookId))) {
                is AppResult.Success -> {
                    updateReady {
                        it.copy(
                            isRestarting = false,
                            isComplete = false,
                            progress = 0f,
                        )
                    }
                    logger.info { "Restarted book $bookId" }
                }

                is AppResult.Failure -> {
                    updateReady { it.copy(isRestarting = false) }
                    logger.error { "Failed to restart book $bookId" }
                }
            }
        }
    }

    /**
     * Force a fresh server-reachability check, backing the offline banner's "Retry"
     * action. Tears down and re-opens the SSE firehose so the reachability indicator
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
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
        viewModelScope.launch {
            updateReady { it.copy(isAddingToShelf = true) }
            when (val result = addBooksToShelfUseCase(ShelfId(shelfId), listOf(BookId(bookId)))) {
                is AppResult.Success -> {
                    updateReady { it.copy(isAddingToShelf = false, showShelfPicker = false) }
                    logger.info { "Added book $bookId to shelf $shelfId" }
                }

                is AppResult.Failure -> {
                    updateReady { it.copy(isAddingToShelf = false, shelfError = result.message) }
                    logger.error { "Failed to add book $bookId to shelf $shelfId: ${result.message}" }
                }
            }
        }
    }

    /**
     * Create a new shelf and add the current book to it.
     */
    fun createShelfAndAddBook(name: String) {
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
        viewModelScope.launch {
            updateReady { it.copy(isAddingToShelf = true) }
            when (val result = createShelfUseCase(name, null)) {
                is AppResult.Success -> {
                    val shelf = result.data
                    when (val addResult = addBooksToShelfUseCase(shelf.id, listOf(BookId(bookId)))) {
                        is AppResult.Success -> {
                            updateReady { it.copy(isAddingToShelf = false, showShelfPicker = false) }
                            logger.info { "Created shelf '${shelf.name}' and added book $bookId" }
                        }

                        is AppResult.Failure -> {
                            updateReady { it.copy(isAddingToShelf = false, shelfError = addResult.message) }
                            logger.error { "Created shelf but failed to add book $bookId: ${addResult.message}" }
                        }
                    }
                }

                is AppResult.Failure -> {
                    updateReady { it.copy(isAddingToShelf = false, shelfError = result.message) }
                    logger.error { "Failed to create shelf '$name': ${result.message}" }
                }
            }
        }
    }

    fun clearShelfError() {
        updateReady { it.copy(shelfError = null) }
    }

    fun clearCollectionError() {
        updateReady { it.copy(collectionError = null) }
    }

    fun showShelfPicker() {
        updateReady { it.copy(showShelfPicker = true) }
    }

    fun hideShelfPicker() {
        updateReady { it.copy(showShelfPicker = false) }
    }

    fun showCollectionPicker() {
        updateReady { it.copy(showCollectionPicker = true) }
    }

    fun hideCollectionPicker() {
        updateReady { it.copy(showCollectionPicker = false) }
    }

    /** Add this book to [collectionId] (additive — never affects the book's All Books membership). */
    fun addBookToCollection(collectionId: String) {
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
        viewModelScope.launch {
            updateReady { it.copy(isAddingToCollection = true) }
            when (val result = collectionRepository.addBook(collectionId, bookId)) {
                is AppResult.Success -> {
                    updateReady { it.copy(isAddingToCollection = false, showCollectionPicker = false) }
                    logger.info { "Added book $bookId to collection $collectionId" }
                }

                is AppResult.Failure -> {
                    updateReady { it.copy(isAddingToCollection = false, collectionError = result.message) }
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
        val book = (state.value as? BookDetailUiState.Ready)?.book ?: return
        val bookId = book.id.value
        viewModelScope.launch {
            updateReady { it.copy(isAddingToCollection = true) }
            when (val result = collectionRepository.create(book.libraryId.value, name)) {
                is AppResult.Success -> {
                    val collection = result.data
                    when (val addResult = collectionRepository.addBook(collection.id, bookId)) {
                        is AppResult.Success -> {
                            updateReady { it.copy(isAddingToCollection = false, showCollectionPicker = false) }
                            logger.info { "Created collection '${collection.name}' and added book $bookId" }
                        }

                        is AppResult.Failure -> {
                            updateReady { it.copy(isAddingToCollection = false, collectionError = addResult.message) }
                            logger.error { "Created collection but failed to add book $bookId: ${addResult.message}" }
                        }
                    }
                }

                is AppResult.Failure -> {
                    updateReady { it.copy(isAddingToCollection = false, collectionError = result.message) }
                    logger.error { "Failed to create collection '$name': ${result.message}" }
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
        val bookId = (state.value as? BookDetailUiState.Ready)?.book?.id?.value ?: return
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

/**
 * UI state for the Book Detail screen.
 *
 * Sealed hierarchy — [Ready] carries all book-dependent fields. Transient
 * action overlays ([isMarkingComplete], [isDiscardingProgress], [isRestarting],
 * [isAddingToShelf]) live on [Ready]; they may later be extracted
 * into a private overlay type.
 */
sealed interface BookDetailUiState {
    /** Pre-load placeholder or in-flight transition between books. */
    data object Loading : BookDetailUiState

    /** Book loaded successfully. */
    data class Ready(
        val book: BookDetail,
        val isAdmin: Boolean = false,
        val isComplete: Boolean = false,
        val startedAtMs: Long? = null,
        val isMarkingComplete: Boolean = false,
        val isDiscardingProgress: Boolean = false,
        val isRestarting: Boolean = false,
        val subtitle: String? = null,
        // Single formatted "Series #N" string (first membership). The Compose UI renders series as
        // chips from book.series, but the iOS SwiftUI Book Detail (BookDetailObserver/BookDetailView)
        // still consumes this string — keep it so iOS compiles and reads series as before.
        val series: String? = null,
        /**
         * Book synopsis for the Description section. Named `descriptionText` (not `description`)
         * deliberately: a Kotlin property called `description` is shadowed on the Swift/SKIE side by
         * the universal `CustomStringConvertible.description`, so `ready.description` there returns
         * the object's `toString()` instead of this field. The distinct name keeps the iOS accessor
         * unambiguous.
         */
        val descriptionText: String = "",
        val narrators: String = "",
        val year: Int? = null,
        val rating: Double? = null,
        val addedAt: Long? = null,
        val hasScanWarning: Boolean = false,
        val chapters: List<ChapterUiModel> = emptyList(),
        val progress: Float? = null,
        val timeRemainingFormatted: String? = null,
        val genres: List<Genre> = emptyList(),
        val tags: List<Tag> = emptyList(),
        val allTags: List<Tag> = emptyList(),
        val moods: List<Mood> = emptyList(),
        val isLoadingTags: Boolean = false,
        val showTagPicker: Boolean = false,
        val showShelfPicker: Boolean = false,
        val isAddingToShelf: Boolean = false,
        val shelfError: String? = null,
        val showCollectionPicker: Boolean = false,
        val isAddingToCollection: Boolean = false,
        val collectionError: String? = null,
        val downloadStatus: BookDownloadStatus = BookDownloadStatus.NotDownloaded(""), // overwritten before emit; "" id never observed
        val isPlaybackAvailable: Boolean = true,
        val canPlay: Boolean = true,
        val canDownload: Boolean = false,
        val showServerWarning: Boolean = false,
        val isWaitingForWifi: Boolean = false,
    ) : BookDetailUiState

    /** Load failure (e.g. the book is not in the local library); carries the typed [error] to localize. */
    data class Error(
        val error: AppError,
    ) : BookDetailUiState
}

/**
 * Per-chapter row data for the book detail screen's chapter list. Pre-formatted
 * for direct display so the Composable layer does no formatting work.
 *
 * [isCurrent] marks the chapter the saved playback position currently sits in,
 * derived in the ViewModel from the position and each chapter's start time. It
 * drives the current-chapter highlight; it is `false` for every chapter when the
 * book has no meaningful progress.
 */
data class ChapterUiModel(
    val id: String,
    val title: String,
    val duration: String,
    val imageUrl: String?,
    val isCurrent: Boolean = false,
)

/**
 * Index of the chapter currently playing: the last chapter whose start time is at or
 * before [positionMs], or null when there are no chapters. Pure — drives the
 * current-chapter highlight from playback position.
 */
internal fun currentChapterIndex(
    chapterStartTimesMs: List<Long>,
    positionMs: Long,
): Int? = chapterStartTimesMs.indexOfLast { it <= positionMs }.takeIf { it >= 0 }

/**
 * Checks if a subtitle is redundant because it's just the series name and book number.
 *
 * Examples of redundant subtitles:
 * - "The Stormlight Archive, Book 1"
 * - "Mistborn #3"
 * - "Book 2 of The Wheel of Time"
 *
 * The heuristic removes the series name and common book number patterns,
 * then checks if there's any meaningful content left.
 */
private fun isSubtitleRedundant(
    subtitle: String,
    seriesName: String?,
    seriesSequence: String?,
): Boolean {
    // If no series info, subtitle is not redundant
    if (seriesName.isNullOrBlank()) return false

    val normalizedSubtitle = subtitle.lowercase().trim()
    val normalizedSeriesName = seriesName.lowercase().trim()

    // Check if subtitle contains the series name
    if (!normalizedSubtitle.contains(normalizedSeriesName)) return false

    // Remove series name from subtitle
    var remaining = normalizedSubtitle.replace(normalizedSeriesName, "")

    // Remove common book number patterns
    val bookNumberPatterns =
        listOf(
            // "Book 1", "Book One", "Book I"
            Regex(
                """book\s*[#]?\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten|i{1,3}|iv|v|vi{0,3}|ix|x)""",
                RegexOption.IGNORE_CASE,
            ),
            // "#1", "# 1"
            Regex("""#\s*\d+"""),
            // "Part 1", "Part One"
            Regex("""part\s*[#]?\s*(\d+|one|two|three|four|five|six|seven|eight|nine|ten)""", RegexOption.IGNORE_CASE),
            // "Volume 1", "Vol. 1", "Vol 1"
            Regex("""vol(ume|\.?)?\s*[#]?\s*\d+""", RegexOption.IGNORE_CASE),
            // Just a number (if sequence matches)
            seriesSequence?.let { Regex("""\b${Regex.escape(it)}\b""") },
        ).filterNotNull()

    for (pattern in bookNumberPatterns) {
        remaining = remaining.replace(pattern, "")
    }

    // Remove common separators and punctuation
    remaining =
        remaining
            .replace(Regex("""[,.:;|\-–—/\\()\[\]{}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    // If very little meaningful content remains (less than 3 chars), it's redundant
    return remaining.length < 3
}

/**
 * One-shot navigation and side-effect events emitted by [BookDetailViewModel].
 *
 * Consumed once at the screen entry point via [BookDetailViewModel.navActions].
 */
sealed interface BookDetailNavAction {
    /**
     * Open the in-app document viewer for the given local file.
     *
     * @param localPath Absolute path to the cached document file on disk, as returned
     *   by [DocumentRepository.ensureLocal].
     */
    data class OpenDocumentViewer(val localPath: String) : BookDetailNavAction

    /**
     * Show a transient snackbar informing the user that a viewer is not yet available
     * for this document format.
     */
    data object ShowViewerComingSoon : BookDetailNavAction
}
