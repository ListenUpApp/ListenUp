
package com.calypsan.listenup.client.presentation.bookedit

import androidx.lifecycle.ViewModel
import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.BookEditData
import com.calypsan.listenup.client.domain.model.BookMetadata
import com.calypsan.listenup.client.domain.model.BookUpdateRequest
import com.calypsan.listenup.client.domain.model.PendingCover
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.book.LoadBookForEditUseCase
import com.calypsan.listenup.client.domain.usecase.SaveOutcome
import com.calypsan.listenup.client.domain.usecase.toNotice
import com.calypsan.listenup.client.domain.usecase.book.UpdateBookUseCase
import com.calypsan.listenup.client.presentation.bookedit.delegates.CollectionEditDelegate
import com.calypsan.listenup.client.presentation.bookedit.delegates.ContributorEditDelegate
import com.calypsan.listenup.client.presentation.bookedit.delegates.CoverUploadDelegate
import com.calypsan.listenup.client.presentation.bookedit.delegates.GenreTagEditDelegate
import com.calypsan.listenup.client.presentation.bookedit.delegates.SeriesEditDelegate
import com.calypsan.listenup.client.presentation.edit.EditPhase
import com.calypsan.listenup.client.presentation.edit.FormSlot
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the book edit screen.
 *
 * Thin presentation coordinator that delegates business logic to use cases:
 * - [LoadBookForEditUseCase]: Loads and transforms book data for editing
 * - [UpdateBookUseCase]: Saves changes with change detection and orchestration
 *
 * Editing operations are handled through focused delegates:
 * - ContributorEditDelegate: Per-role contributor search and management
 * - SeriesEditDelegate: Series search and management
 * - GenreTagEditDelegate: Genre filtering and tag creation
 * - CoverUploadDelegate: Cover staging and upload
 */
@Suppress("TooManyFunctions")
class BookEditViewModel(
    private val loadBookForEditUseCase: LoadBookForEditUseCase,
    private val updateBookUseCase: UpdateBookUseCase,
    contributorRepository: ContributorRepository,
    seriesRepository: SeriesRepository,
    collectionRepository: CollectionRepository,
    private val bookEditRepository: BookEditRepository,
    userRepository: UserRepository,
    private val inboxRepository: InboxRepository,
    imageStagingRepository: ImageStagingRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    // Use explicit backing field for mutable state shared with delegates
    val state: StateFlow<BookEditUiState>
        field = MutableStateFlow<BookEditUiState>(BookEditUiState.Loading)

    private val navActionsChannel = Channel<BookEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<BookEditNavAction> = navActionsChannel.receiveAsFlow()

    /** The form inside [BookEditUiState.Editing], for the delegates that edit one slice of it. */
    private val form: FormSlot<BookEditUiState, BookEditForm> =
        FormSlot(
            state = state,
            formOf = { (it as? BookEditUiState.Editing)?.form },
            withForm = {
                current,
                form,
                ->
                if (current is BookEditUiState.Editing) current.copy(form = form) else current
            },
        )

    /** Admin status gates the Collections field; held live so a form built later starts with it. */
    private val adminStatus: StateFlow<Boolean> =
        userRepository.observeIsAdmin().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // The held-for-review observation for the loaded book; replaced when another book loads.
    private var heldObservation: Job? = null

    /** The id whose data the form currently holds or is fetching; a repeat load for it is a no-op. */
    private var loadedBookId: String? = null
    private var loadJob: Job? = null

    // Delegates for focused editing operations
    private val contributorDelegate =
        ContributorEditDelegate(
            state = form,
            contributorRepository = contributorRepository,
            scope = viewModelScope,
            onChangesMade = ::updateHasChanges,
        )

    private val seriesDelegate =
        SeriesEditDelegate(
            state = form,
            seriesRepository = seriesRepository,
            scope = viewModelScope,
            onChangesMade = ::updateHasChanges,
        )

    private val genreTagDelegate =
        GenreTagEditDelegate(
            state = form,
            scope = viewModelScope,
            onChangesMade = ::updateHasChanges,
        )

    private val collectionDelegate =
        CollectionEditDelegate(
            state = form,
            collectionRepository = collectionRepository,
            scope = viewModelScope,
            onChangesMade = ::updateHasChanges,
        )

    private val coverDelegate =
        CoverUploadDelegate(
            state = state,
            imageStagingRepository = imageStagingRepository,
            scope = viewModelScope,
            errorBus = errorBus,
            onChangesMade = ::updateHasChanges,
        )

    init {
        // Admin status gates the Collections field — observe reactively so the UI
        // hides it for members.
        viewModelScope.launch {
            adminStatus.collect { admin -> form.update { it.copy(isAdmin = admin) } }
        }
    }

    /**
     * Load book data for editing.
     *
     * Delegates to [LoadBookForEditUseCase] which handles:
     * - Fetching book from repository
     * - Transforming to editable format
     * - Loading all genres and tags for pickers
     */
    fun loadBook(bookId: String) {
        // The screen asks again whenever it is recreated (rotation, theme, fold). The form already
        // holds this book, possibly with unsaved edits, so there is nothing to fetch.
        if (loadedBookId == bookId) return
        loadedBookId = bookId

        heldObservation?.cancel()
        loadJob?.cancel()
        state.value = BookEditUiState.Loading
        loadJob =
            viewModelScope.launch {
                when (val result = loadBookForEditUseCase(bookId)) {
                    is AppResult.Success -> {
                        val editData = result.data

                        // Always show Author section, plus any roles that have contributors
                        val initialVisibleRoles =
                            editData.contributors.flatMap { it.roles }.toSet() + ContributorRole.AUTHOR

                        state.value =
                            BookEditUiState.Editing(
                                form = editData.toForm(bookId, initialVisibleRoles, adminStatus.value),
                                original = editData,
                            )

                        // Set up search for each visible role
                        initialVisibleRoles.forEach { role ->
                            contributorDelegate.setupRoleSearch(role)
                        }
                        // The book's collection memberships + the available list, and whether it is
                        // held — reactive, so they follow Room for as long as the form is open.
                        collectionDelegate.loadCollections(bookId)
                        heldObservation =
                            viewModelScope.launch {
                                inboxRepository
                                    .observeHeldBookIds()
                                    .map { held -> BookId(bookId) in held }
                                    .distinctUntilChanged()
                                    .collect { held -> form.update { it.copy(isHeld = held) } }
                            }

                        logger.debug { "Loaded book for editing: ${editData.metadata.title}" }
                    }

                    is AppResult.Failure -> {
                        // Let the same id be asked for again: nothing was loaded.
                        loadedBookId = null
                        errorBus.emit(result.error)
                        state.value = BookEditUiState.LoadFailed(bookId, result.error)
                    }
                }
            }
    }

    private fun BookEditData.toForm(
        bookId: String,
        visibleRoles: Set<ContributorRole>,
        isAdmin: Boolean,
    ): BookEditForm =
        BookEditForm(
            bookId = bookId,
            coverPath = coverPath,
            coverHash = coverHash,
            title = metadata.title,
            sortTitle = metadata.sortTitle,
            subtitle = metadata.subtitle,
            description = metadata.description,
            publishYear = metadata.publishYear,
            publisher = metadata.publisher,
            language = metadata.language,
            isbn = metadata.isbn,
            asin = metadata.asin,
            abridged = metadata.abridged,
            addedAt = metadata.addedAt,
            contributors = contributors,
            series = series,
            visibleRoles = visibleRoles,
            genres = genres,
            allGenres = allGenres,
            tags = tags,
            allTags = allTags,
            moods = moods,
            allMoods = allMoods,
            isAdmin = isAdmin,
        )

    /**
     * Handle UI events by routing to appropriate delegates.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun onEvent(event: BookEditUiEvent) {
        when (event) {
            // Metadata changes - handled directly
            is BookEditUiEvent.TitleChanged -> {
                form.update { it.copy(title = event.title) }
                updateHasChanges()
            }

            is BookEditUiEvent.SortTitleChanged -> {
                form.update { it.copy(sortTitle = event.sortTitle) }
                updateHasChanges()
            }

            is BookEditUiEvent.SubtitleChanged -> {
                form.update { it.copy(subtitle = event.subtitle) }
                updateHasChanges()
            }

            is BookEditUiEvent.DescriptionChanged -> {
                form.update { it.copy(description = event.description) }
                updateHasChanges()
            }

            is BookEditUiEvent.PublishYearChanged -> {
                // Stored verbatim: the field owns the typed text and restricts input itself (an
                // input transformation on ListenUpTextField), so no transform runs between field
                // and state. The digit filter at the save boundary in toMetadata() stays as a
                // second line of defense.
                form.update { it.copy(publishYear = event.year) }
                updateHasChanges()
            }

            is BookEditUiEvent.PublisherChanged -> {
                form.update { it.copy(publisher = event.publisher) }
                updateHasChanges()
            }

            is BookEditUiEvent.LanguageChanged -> {
                form.update { it.copy(language = event.code) }
                updateHasChanges()
            }

            is BookEditUiEvent.IsbnChanged -> {
                form.update { it.copy(isbn = event.isbn) }
                updateHasChanges()
            }

            is BookEditUiEvent.AsinChanged -> {
                form.update { it.copy(asin = event.asin) }
                updateHasChanges()
            }

            is BookEditUiEvent.AbridgedChanged -> {
                form.update { it.copy(abridged = event.abridged) }
                updateHasChanges()
            }

            is BookEditUiEvent.AddedAtChanged -> {
                form.update { it.copy(addedAt = event.epochMillis) }
                updateHasChanges()
            }

            // Series events - delegate to SeriesEditDelegate
            is BookEditUiEvent.SeriesSearchQueryChanged -> {
                seriesDelegate.updateSearchQuery(event.query)
            }

            is BookEditUiEvent.SeriesSelected -> {
                seriesDelegate.selectSeries(event.result)
            }

            is BookEditUiEvent.SeriesEntered -> {
                seriesDelegate.addSeries(event.name)
            }

            is BookEditUiEvent.SeriesSequenceChanged -> {
                seriesDelegate.updateSeriesSequence(event.series, event.sequence)
            }

            is BookEditUiEvent.RemoveSeries -> {
                seriesDelegate.removeSeries(event.series)
            }

            // Contributor events - delegate to ContributorEditDelegate
            is BookEditUiEvent.RoleSearchQueryChanged -> {
                contributorDelegate.updateSearchQuery(event.role, event.query)
            }

            is BookEditUiEvent.RoleContributorSelected -> {
                contributorDelegate.selectContributor(event.role, event.result)
            }

            is BookEditUiEvent.RoleContributorEntered -> {
                contributorDelegate.addContributor(event.role, event.name)
            }

            is BookEditUiEvent.AddRoleSection -> {
                contributorDelegate.addRoleSection(event.role)
            }

            is BookEditUiEvent.RemoveContributor -> {
                contributorDelegate.removeContributorFromRole(event.contributor, event.role)
            }

            is BookEditUiEvent.RemoveRoleSection -> {
                contributorDelegate.removeRoleSection(event.role)
            }

            // Genre events - delegate to GenreTagEditDelegate
            is BookEditUiEvent.GenreSearchQueryChanged -> {
                genreTagDelegate.updateGenreSearchQuery(event.query)
            }

            is BookEditUiEvent.GenreSelected -> {
                genreTagDelegate.selectGenre(event.genre)
            }

            is BookEditUiEvent.RemoveGenre -> {
                genreTagDelegate.removeGenre(event.genre)
            }

            // Tag events - delegate to GenreTagEditDelegate
            is BookEditUiEvent.TagSearchQueryChanged -> {
                genreTagDelegate.updateTagSearchQuery(event.query)
            }

            is BookEditUiEvent.TagSelected -> {
                genreTagDelegate.selectTag(event.tag)
            }

            is BookEditUiEvent.TagEntered -> {
                genreTagDelegate.createAndAddTag(event.name)
            }

            is BookEditUiEvent.RemoveTag -> {
                genreTagDelegate.removeTag(event.tag)
            }

            // Mood events - delegate to GenreTagEditDelegate
            is BookEditUiEvent.MoodSearchQueryChanged -> {
                genreTagDelegate.updateMoodSearchQuery(event.query)
            }

            is BookEditUiEvent.MoodSelected -> {
                genreTagDelegate.selectMood(event.mood)
            }

            is BookEditUiEvent.MoodEntered -> {
                genreTagDelegate.createAndAddMood(event.name)
            }

            is BookEditUiEvent.RemoveMood -> {
                genreTagDelegate.removeMood(event.mood)
            }

            // Collection events - delegate to CollectionEditDelegate
            is BookEditUiEvent.CollectionSearchQueryChanged -> {
                collectionDelegate.updateCollectionSearchQuery(event.query)
            }

            is BookEditUiEvent.CollectionSelected -> {
                collectionDelegate.selectCollection(event.collection)
            }

            is BookEditUiEvent.RemoveCollection -> {
                collectionDelegate.removeCollection(event.collection)
            }

            // Cover events - delegate to CoverUploadDelegate
            is BookEditUiEvent.UploadCover -> {
                coverDelegate.uploadCover(event.imageData, event.filename)
            }

            // Actions - handled directly
            is BookEditUiEvent.Save -> {
                saveChanges()
            }

            is BookEditUiEvent.Cancel -> {
                cancelAndCleanup()
            }

            is BookEditUiEvent.DismissError -> {
                updateEditing { it.copy(inlineError = null) }
            }

            is BookEditUiEvent.RetryLoad -> {
                (state.value as? BookEditUiState.LoadFailed)?.let { loadBook(it.bookId) }
            }
        }
    }

    /**
     * Clean up staging files when ViewModel is destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        coverDelegate.cleanupStagingOnClear()
    }

    // ========== Private Methods ==========

    private fun cancelAndCleanup() {
        coverDelegate.cleanupStagingOnCancel()
        navActionsChannel.trySend(BookEditNavAction.NavigateBack)
    }

    /**
     * Update hasChanges flag by comparing current state to original.
     *
     * Uses data class equality on [BookMetadata] for clean comparison.
     */
    private fun updateHasChanges() {
        updateEditing { editing ->
            val current = editing.form
            val original = editing.original
            val hasChanges =
                current.toMetadata() != original.metadata ||
                    current.contributors != original.contributors ||
                    current.series != original.series ||
                    current.genres != original.genres ||
                    current.tags != original.tags ||
                    current.moods != original.moods ||
                    collectionDelegate.hasChanges() ||
                    current.pendingCoverData != null
            editing.copy(hasChanges = hasChanges)
        }
    }

    private inline fun updateEditing(transform: (BookEditUiState.Editing) -> BookEditUiState.Editing) {
        state.update { if (it is BookEditUiState.Editing) transform(it) else it }
    }

    /**
     * Save changes using [UpdateBookUseCase].
     *
     * The use case handles:
     * - Change detection (comparing current vs original)
     * - Orchestrating repository calls in correct order
     * - Error handling with fail-fast semantics
     *
     * Refused unless the form is idle, so a second tap while the first Save is in flight is a no-op.
     * A failure stays on the form as [BookEditUiState.Editing.inlineError], edits intact.
     */
    private fun saveChanges() {
        val editing = state.value as? BookEditUiState.Editing ?: return
        if (editing.phase != EditPhase.Idle) return

        if (!editing.hasChanges) {
            navActionsChannel.trySend(BookEditNavAction.NavigateBack)
            return
        }
        state.value = editing.copy(phase = EditPhase.Saving, inlineError = null)
        val current = editing.form

        viewModelScope.launch {
            when (val result = updateBookUseCase(current.toUpdateRequest(), editing.original)) {
                is AppResult.Success -> {
                    // The save went through; a cover that did not reach the server is said out loud.
                    (result.data as? SaveOutcome.SavedImageNotUploaded)?.let { errorBus.emit(it.toNotice()) }
                    // Collections persist via a dedicated access-aware RPC, not the
                    // UpdateBookUseCase — dispatch only when the set actually changed.
                    val collectionsResult =
                        if (collectionDelegate.hasChanges()) saveCollections(current) else null
                    if (collectionsResult is AppResult.Failure) {
                        errorBus.emit(collectionsResult.error)
                        updateEditing { it.copy(phase = EditPhase.Idle, inlineError = collectionsResult.error) }
                        return@launch
                    }

                    updateEditing { editState ->
                        editState.copy(
                            phase = EditPhase.Idle,
                            hasChanges = false,
                            form =
                                editState.form.copy(
                                    pendingCoverData = null,
                                    pendingCoverFilename = null,
                                    stagingCoverPath = null,
                                ),
                        )
                    }
                    navActionsChannel.trySend(BookEditNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = result.error) }
                }
            }
        }
    }

    /**
     * Dispatch the current collection set via the access-aware [BookEditRepository.setBookCollections]
     * RPC. No optimistic Room write — the firehose echo + AccessChanged reconcile deliver the new state.
     */
    private suspend fun saveCollections(current: BookEditForm) =
        bookEditRepository.setBookCollections(
            id = BookId(current.bookId),
            collectionIds = current.collections.map { it.id },
        )

    /**
     * Build [BookMetadata] from the form.
     */
    private fun BookEditForm.toMetadata(): BookMetadata =
        BookMetadata(
            title = title,
            sortTitle = sortTitle,
            subtitle = subtitle,
            description = description,
            // The field's live value is verbatim (echo-verbatim contract); normalize here, once.
            publishYear = publishYear.filter { it.isDigit() }.take(4),
            publisher = publisher,
            language = language,
            isbn = isbn,
            asin = asin,
            abridged = abridged,
            addedAt = addedAt,
        )

    /**
     * Build [BookUpdateRequest] from the form.
     */
    private fun BookEditForm.toUpdateRequest(): BookUpdateRequest =
        BookUpdateRequest(
            bookId = bookId,
            metadata = toMetadata(),
            contributors = contributors,
            series = series,
            genres = genres,
            tags = tags,
            moods = moods,
            pendingCover =
                if (pendingCoverData != null && pendingCoverFilename != null) {
                    PendingCover(
                        data = pendingCoverData,
                        filename = pendingCoverFilename,
                    )
                } else {
                    null
                },
        )
}
