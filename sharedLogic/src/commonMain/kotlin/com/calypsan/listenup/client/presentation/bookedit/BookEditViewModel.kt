@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.presentation.bookedit

import androidx.lifecycle.ViewModel
import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.BookEditData
import com.calypsan.listenup.client.domain.model.BookMetadata
import com.calypsan.listenup.client.domain.model.BookUpdateRequest
import com.calypsan.listenup.client.domain.model.PendingCover
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.book.LoadBookForEditUseCase
import com.calypsan.listenup.client.domain.usecase.book.UpdateBookUseCase
import com.calypsan.listenup.client.presentation.bookedit.delegates.CollectionEditDelegate
import com.calypsan.listenup.client.presentation.bookedit.delegates.ContributorEditDelegate
import com.calypsan.listenup.client.presentation.bookedit.delegates.CoverUploadDelegate
import com.calypsan.listenup.client.presentation.bookedit.delegates.GenreTagEditDelegate
import com.calypsan.listenup.client.presentation.bookedit.delegates.SeriesEditDelegate
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val imageStagingRepository: ImageStagingRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    // Use traditional pattern for mutable state shared with delegates
    private val _state = MutableStateFlow(BookEditUiState())
    val state: StateFlow<BookEditUiState> = _state

    private val _navActions = Channel<BookEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<BookEditNavAction> = _navActions.receiveAsFlow()

    // Original state for change detection (set when book is loaded)
    private var originalState: BookEditData? = null

    // Delegates for focused editing operations
    private val contributorDelegate =
        ContributorEditDelegate(
            state = _state,
            contributorRepository = contributorRepository,
            scope = viewModelScope,
            onChangesMade = ::updateHasChanges,
        )

    private val seriesDelegate =
        SeriesEditDelegate(
            state = _state,
            seriesRepository = seriesRepository,
            scope = viewModelScope,
            onChangesMade = ::updateHasChanges,
        )

    private val genreTagDelegate =
        GenreTagEditDelegate(
            state = _state,
            scope = viewModelScope,
            onChangesMade = ::updateHasChanges,
        )

    private val collectionDelegate =
        CollectionEditDelegate(
            state = _state,
            collectionRepository = collectionRepository,
            scope = viewModelScope,
            onChangesMade = ::updateHasChanges,
        )

    private val coverDelegate =
        CoverUploadDelegate(
            state = _state,
            imageStagingRepository = imageStagingRepository,
            scope = viewModelScope,
            errorBus = errorBus,
            onChangesMade = ::updateHasChanges,
        )

    init {
        // Admin status gates the Collections field — observe reactively so the UI
        // hides it for members.
        viewModelScope.launch {
            userRepository.observeIsAdmin().collect { isAdmin ->
                _state.update { it.copy(isAdmin = isAdmin) }
            }
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
        // Start observing the book's collection memberships + the available list.
        // Reactive, so it runs alongside the one-shot use-case load below.
        collectionDelegate.loadCollections(bookId)
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, bookId = bookId) }

            when (val result = loadBookForEditUseCase(bookId)) {
                is AppResult.Success -> {
                    val editData = result.data
                    originalState = editData

                    // Determine visible roles from existing contributors
                    val rolesFromContributors =
                        editData.contributors
                            .flatMap { it.roles }
                            .toSet()

                    // Always show Author section, plus any roles that have contributors
                    val initialVisibleRoles = rolesFromContributors + ContributorRole.AUTHOR

                    // Set up search for each visible role
                    initialVisibleRoles.forEach { role ->
                        contributorDelegate.setupRoleSearch(role)
                    }

                    _state.update {
                        it.copy(
                            isLoading = false,
                            coverPath = editData.coverPath,
                            title = editData.metadata.title,
                            sortTitle = editData.metadata.sortTitle,
                            subtitle = editData.metadata.subtitle,
                            description = editData.metadata.description,
                            publishYear = editData.metadata.publishYear,
                            publisher = editData.metadata.publisher,
                            language = editData.metadata.language,
                            isbn = editData.metadata.isbn,
                            asin = editData.metadata.asin,
                            abridged = editData.metadata.abridged,
                            addedAt = editData.metadata.addedAt,
                            contributors = editData.contributors,
                            series = editData.series,
                            visibleRoles = initialVisibleRoles,
                            genres = editData.genres,
                            allGenres = editData.allGenres,
                            tags = editData.tags,
                            allTags = editData.allTags,
                            hasChanges = false,
                        )
                    }

                    logger.debug {
                        "Loaded book for editing: ${editData.metadata.title}"
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    /**
     * Handle UI events by routing to appropriate delegates.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun onEvent(event: BookEditUiEvent) {
        when (event) {
            // Metadata changes - handled directly
            is BookEditUiEvent.TitleChanged -> {
                _state.update { it.copy(title = event.title) }
                updateHasChanges()
            }

            is BookEditUiEvent.SortTitleChanged -> {
                _state.update { it.copy(sortTitle = event.sortTitle) }
                updateHasChanges()
            }

            is BookEditUiEvent.SubtitleChanged -> {
                _state.update { it.copy(subtitle = event.subtitle) }
                updateHasChanges()
            }

            is BookEditUiEvent.DescriptionChanged -> {
                _state.update { it.copy(description = event.description) }
                updateHasChanges()
            }

            is BookEditUiEvent.PublishYearChanged -> {
                val filtered = event.year.filter { it.isDigit() }.take(4)
                _state.update { it.copy(publishYear = filtered) }
                updateHasChanges()
            }

            is BookEditUiEvent.PublisherChanged -> {
                _state.update { it.copy(publisher = event.publisher) }
                updateHasChanges()
            }

            is BookEditUiEvent.LanguageChanged -> {
                _state.update { it.copy(language = event.code) }
                updateHasChanges()
            }

            is BookEditUiEvent.IsbnChanged -> {
                _state.update { it.copy(isbn = event.isbn) }
                updateHasChanges()
            }

            is BookEditUiEvent.AsinChanged -> {
                _state.update { it.copy(asin = event.asin) }
                updateHasChanges()
            }

            is BookEditUiEvent.AbridgedChanged -> {
                _state.update { it.copy(abridged = event.abridged) }
                updateHasChanges()
            }

            is BookEditUiEvent.AddedAtChanged -> {
                _state.update { it.copy(addedAt = event.epochMillis) }
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

            is BookEditUiEvent.ClearSeriesSearch -> {
                seriesDelegate.clearSearch()
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

            is BookEditUiEvent.ClearRoleSearch -> {
                contributorDelegate.clearSearch(event.role)
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
                _state.update { it.copy(error = null) }
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
        _navActions.trySend(BookEditNavAction.NavigateBack)
    }

    /**
     * Update hasChanges flag by comparing current state to original.
     *
     * Uses data class equality on [BookMetadata] for clean comparison.
     */
    private fun updateHasChanges() {
        val original = originalState ?: return
        val current = _state.value

        val currentMetadata = current.toMetadata()
        val hasChanges =
            currentMetadata != original.metadata ||
                current.contributors != original.contributors ||
                current.series != original.series ||
                current.genres != original.genres ||
                current.tags != original.tags ||
                collectionDelegate.hasChanges() ||
                current.pendingCoverData != null

        _state.update { it.copy(hasChanges = hasChanges) }
    }

    /**
     * Save changes using [UpdateBookUseCase].
     *
     * The use case handles:
     * - Change detection (comparing current vs original)
     * - Orchestrating repository calls in correct order
     * - Error handling with fail-fast semantics
     */
    private fun saveChanges() {
        val original = originalState ?: return
        val current = _state.value

        if (!current.hasChanges) {
            _navActions.trySend(BookEditNavAction.NavigateBack)
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }

            val updateRequest = current.toUpdateRequest()

            when (val result = updateBookUseCase(updateRequest, original)) {
                is AppResult.Success -> {
                    // Collections persist via a dedicated access-aware RPC, not the
                    // UpdateBookUseCase — dispatch only when the set actually changed.
                    val collectionsResult =
                        if (collectionDelegate.hasChanges()) saveCollections(current) else null
                    if (collectionsResult is AppResult.Failure) {
                        errorBus.emit(collectionsResult.error)
                        _state.update {
                            it.copy(isSaving = false, error = collectionsResult.message)
                        }
                        return@launch
                    }

                    _state.update {
                        it.copy(
                            isSaving = false,
                            hasChanges = false,
                            pendingCoverData = null,
                            pendingCoverFilename = null,
                            stagingCoverPath = null,
                        )
                    }
                    _navActions.trySend(BookEditNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    _state.update {
                        it.copy(isSaving = false, error = result.message)
                    }
                }
            }
        }
    }

    /**
     * Dispatch the current collection set via the access-aware [BookEditRepository.setBookCollections]
     * RPC. No optimistic Room write — the SSE echo + AccessChanged reconcile deliver the new state.
     */
    private suspend fun saveCollections(current: BookEditUiState) =
        bookEditRepository.setBookCollections(
            id = BookId(current.bookId),
            collectionIds = current.collections.map { it.id },
        )

    /**
     * Build [BookMetadata] from current UI state.
     */
    private fun BookEditUiState.toMetadata(): BookMetadata =
        BookMetadata(
            title = title,
            sortTitle = sortTitle,
            subtitle = subtitle,
            description = description,
            publishYear = publishYear,
            publisher = publisher,
            language = language,
            isbn = isbn,
            asin = asin,
            abridged = abridged,
            addedAt = addedAt,
        )

    /**
     * Build [BookUpdateRequest] from current UI state.
     */
    private fun BookEditUiState.toUpdateRequest(): BookUpdateRequest =
        BookUpdateRequest(
            bookId = bookId,
            metadata = toMetadata(),
            contributors = contributors,
            series = series,
            genres = genres,
            tags = tags,
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
