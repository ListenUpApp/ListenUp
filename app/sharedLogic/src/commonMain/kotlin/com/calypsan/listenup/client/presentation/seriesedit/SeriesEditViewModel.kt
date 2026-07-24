package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.series.SeriesUpdateRequest
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** Maximum number of merge-target candidates surfaced in the picker dialog. */
private const val MAX_MERGE_CANDIDATES = 30

/** Idle timeout before stopping merge-candidate collection. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * Lightweight projection of a series as a merge-target candidate.
 *
 * Used by [SeriesEditViewModel.mergeCandidates] to populate the series merge
 * picker dialog. [bookCount] is a placeholder (always `0`) — there is no per-series
 * book-count query yet, so the dialog hides it.
 */
data class SeriesCandidate(
    val id: SeriesId,
    val displayName: String,
    val bookCount: Int,
)

/**
 * UI state for series editing screen.
 */
data class SeriesEditUiState(
    // Loading states
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isUploadingCover: Boolean = false,
    val error: String? = null,
    // Series identity
    val seriesId: String = "",
    val name: String = "",
    val description: String = "",
    // Cover management
    val coverPath: String? = null,
    val stagingCoverPath: String? = null,
    val pendingCoverData: ByteArray? = null,
    val pendingCoverFilename: String? = null,
    // Display metadata
    val bookCount: Int = 0,
    // Merge (server-canonical; firehose delivers result)
    val mergeInProgress: Boolean = false,
    // Merge-target picker query (drives candidate filtering)
    val mergeQuery: String = "",
    // Track if changes have been made
    val hasChanges: Boolean = false,
) {
    /**
     * Returns the cover path to display - staging if available, otherwise original.
     */
    val displayCoverPath: String?
        get() = stagingCoverPath ?: coverPath
}

/**
 * Events from the series edit UI.
 */
sealed interface SeriesEditUiEvent {
    /** User edited the series name field. */
    data class NameChanged(
        val name: String,
    ) : SeriesEditUiEvent

    /** User edited the series description field. */
    data class DescriptionChanged(
        val description: String,
    ) : SeriesEditUiEvent

    /** User chose an image to use as the series cover; bytes are staged until Save. */
    data class CoverSelected(
        val imageData: ByteArray,
        val filename: String,
    ) : SeriesEditUiEvent

    data object CoverRemoved : SeriesEditUiEvent

    data object SaveClicked : SeriesEditUiEvent

    data object CancelClicked : SeriesEditUiEvent

    data object ErrorDismissed : SeriesEditUiEvent

    /** User chose to merge the current series into [targetId]. */
    data class MergeInto(
        val targetId: SeriesId,
    ) : SeriesEditUiEvent
}

/**
 * Navigation actions from series edit screen.
 */
sealed interface SeriesEditNavAction {
    data object NavigateBack : SeriesEditNavAction
}

/**
 * ViewModel for the series edit screen.
 *
 * Handles:
 * - Loading series data for editing
 * - Saving metadata changes
 * - Cover image staging and upload
 * - Server-canonical merge via [SeriesEditRepository]
 * - Tracking unsaved changes
 *
 * @property seriesRepository Repository for loading series data
 * @property updateSeriesUseCase Use case for saving series changes
 * @property imageRepository Repository for persistent cover image operations
 * @property imageStagingRepository Repository for staging cover image operations
 * @property seriesEditRepository RPC dispatcher for merge
 * @property seriesDao DAO for browsing all series as merge-target candidates
 * @property errorBus Global error bus for snackbar emissions
 */
class SeriesEditViewModel internal constructor(
    private val seriesRepository: SeriesRepository,
    private val updateSeriesUseCase: UpdateSeriesUseCase,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
    private val seriesEditRepository: SeriesEditRepository,
    private val seriesDao: SeriesDao,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<SeriesEditUiState>
        field = MutableStateFlow(SeriesEditUiState())

    private val _navActions = Channel<SeriesEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<SeriesEditNavAction> = _navActions.receiveAsFlow()

    /**
     * Candidates for the merge-target picker — all live series except the current
     * one, filtered by [SeriesEditUiState.mergeQuery] (case-insensitive substring).
     * Capped at [MAX_MERGE_CANDIDATES] to keep the dialog snappy.
     */
    val mergeCandidates: StateFlow<List<SeriesCandidate>> =
        combine(state, seriesDao.observeAll()) { uiState, allSeries ->
            val currentId = uiState.seriesId
            val query = uiState.mergeQuery
            allSeries
                .asSequence()
                .filter { it.deletedAt == null }
                .filter { it.id.value != currentId }
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name.lowercase() }
                .take(MAX_MERGE_CANDIDATES)
                .map { entity ->
                    SeriesCandidate(
                        id = entity.id,
                        displayName = entity.name,
                        bookCount = 0,
                    )
                }.toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Update the merge-target picker's search query. The [mergeCandidates] Flow
     * re-emits a filtered list whenever this changes.
     */
    fun onMergeQueryChange(query: String) {
        state.update { it.copy(mergeQuery = query) }
    }

    // Track original values for change detection
    private var originalName: String = ""
    private var originalDescription: String = ""
    private var originalCoverPath: String? = null

    /**
     * Load series data for editing.
     */
    fun loadSeries(seriesId: String) {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true, seriesId = seriesId) }

            val series = seriesRepository.getById(seriesId)
            if (series == null) {
                state.update { it.copy(isLoading = false, error = "Series not found") }
                return@launch
            }

            val bookCount = seriesRepository.getBookIdsForSeries(seriesId).size

            // Get cover path if it exists
            val coverPath =
                if (imageRepository.seriesCoverExists(seriesId)) {
                    imageRepository.getSeriesCoverPath(seriesId)
                } else {
                    null
                }

            // Store original values
            originalName = series.name
            originalDescription = series.description ?: ""
            originalCoverPath = coverPath

            state.update {
                it.copy(
                    isLoading = false,
                    name = series.name,
                    description = series.description ?: "",
                    coverPath = coverPath,
                    bookCount = bookCount,
                    hasChanges = false,
                )
            }

            logger.debug { "Loaded series for editing: ${series.name}, bookCount=$bookCount" }
        }
    }

    /**
     * Handle UI events.
     */
    fun onEvent(event: SeriesEditUiEvent) {
        when (event) {
            is SeriesEditUiEvent.NameChanged -> {
                state.update { it.copy(name = event.name) }
                updateHasChanges()
            }

            is SeriesEditUiEvent.DescriptionChanged -> {
                state.update { it.copy(description = event.description) }
                updateHasChanges()
            }

            is SeriesEditUiEvent.CoverSelected -> {
                handleCoverSelected(event.imageData, event.filename)
            }

            is SeriesEditUiEvent.CoverRemoved -> {
                handleCoverRemoved()
            }

            is SeriesEditUiEvent.SaveClicked -> {
                saveChanges()
            }

            is SeriesEditUiEvent.CancelClicked -> {
                cancelAndCleanup()
            }

            is SeriesEditUiEvent.ErrorDismissed -> {
                state.update { it.copy(error = null) }
            }

            is SeriesEditUiEvent.MergeInto -> {
                mergeInto(event.targetId)
            }
        }
    }

    /**
     * Merge the current series into [targetId]. After firehose delivery the source is
     * soft-deleted and all of its books re-point at the target; we navigate back.
     */
    private fun mergeInto(targetId: SeriesId) {
        val sourceId = state.value.seriesId
        if (sourceId.isBlank()) {
            logger.error { "Cannot merge: series ID is empty" }
            return
        }

        viewModelScope.launch {
            state.update { it.copy(mergeInProgress = true, error = null) }

            when (val result = seriesEditRepository.mergeSeries(SeriesId(sourceId), targetId)) {
                is AppResult.Success -> {
                    state.update { it.copy(mergeInProgress = false) }
                    _navActions.trySend(SeriesEditNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to merge series: ${result.message}" }
                    state.update {
                        it.copy(
                            mergeInProgress = false,
                            error =
                                when (result.error) {
                                    is SeriesError.MergeSelfTarget -> "Can't merge a series with itself."
                                    is SeriesError.NotFound -> "One of these series no longer exists."
                                    else -> result.error.message
                                },
                        )
                    }
                }
            }
        }
    }

    /**
     * Update hasChanges flag based on current vs original values.
     */
    private fun updateHasChanges() {
        val current = state.value
        val hasChanges =
            current.name != originalName ||
                current.description != originalDescription ||
                current.pendingCoverData != null // Cover changed if we have pending data

        state.update { it.copy(hasChanges = hasChanges) }
    }

    /**
     * Handle cover selection.
     * Saves the image to a staging location for preview.
     * Does NOT overwrite the main cover until saveChanges() is called.
     */
    private fun handleCoverSelected(
        imageData: ByteArray,
        filename: String,
    ) {
        val seriesId = state.value.seriesId
        if (seriesId.isBlank()) {
            logger.error { "Cannot set cover: series ID is empty" }
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isUploadingCover = true, error = null) }

            // Save to staging location for preview (doesn't overwrite original)
            when (val saveResult = imageStagingRepository.saveSeriesCoverStaging(seriesId, imageData)) {
                is AppResult.Success -> {
                    val stagingPath = imageStagingRepository.getSeriesCoverStagingPath(seriesId)
                    logger.info { "Cover saved to staging for preview: $stagingPath" }

                    // Store pending data for upload when Save Changes is clicked
                    state.update {
                        it.copy(
                            isUploadingCover = false,
                            stagingCoverPath = stagingPath,
                            pendingCoverData = imageData,
                            pendingCoverFilename = filename,
                        )
                    }
                    updateHasChanges()
                }

                is AppResult.Failure -> {
                    errorBus.emit(saveResult.error)
                    logger.error { "Failed to save cover to staging: ${saveResult.message}" }
                    state.update {
                        it.copy(
                            isUploadingCover = false,
                            error = "Failed to save cover: ${saveResult.message}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Handle cover removal.
     * Deletes the staging cover and clears pending data.
     */
    private fun handleCoverRemoved() {
        val seriesId = state.value.seriesId
        if (seriesId.isBlank()) {
            logger.error { "Cannot remove cover: series ID is empty" }
            return
        }

        if (state.value.stagingCoverPath != null) {
            imageStagingRepository.requestSeriesCoverStagingCleanup(seriesId)
        }

        state.update {
            it.copy(
                stagingCoverPath = null,
                pendingCoverData = null,
                pendingCoverFilename = null,
            )
        }
        updateHasChanges()

        logger.debug { "Staging cover removed" }
    }

    /**
     * Save all changes via the use case.
     */
    private fun saveChanges() {
        val current = state.value
        if (!current.hasChanges) {
            _navActions.trySend(SeriesEditNavAction.NavigateBack)
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isSaving = true, error = null) }

            val metadataChanged = current.name != originalName || current.description != originalDescription

            val result =
                updateSeriesUseCase(
                    SeriesUpdateRequest(
                        seriesId = current.seriesId,
                        name = current.name,
                        description = current.description,
                        metadataChanged = metadataChanged,
                        nameChanged = current.name != originalName,
                        descriptionChanged = current.description != originalDescription,
                        pendingCoverData = current.pendingCoverData,
                        pendingCoverFilename = current.pendingCoverFilename,
                    ),
                )

            when (result) {
                is AppResult.Success -> {
                    state.update {
                        it.copy(
                            isSaving = false,
                            hasChanges = false,
                            pendingCoverData = null,
                            pendingCoverFilename = null,
                            stagingCoverPath = null,
                        )
                    }
                    _navActions.trySend(SeriesEditNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to save series: ${result.message}" }
                    state.update { it.copy(isSaving = false, error = "Failed to save: ${result.message}") }
                }
            }
        }
    }

    /**
     * Cancel editing and clean up any staging files.
     */
    private fun cancelAndCleanup() {
        val seriesId = state.value.seriesId
        if (seriesId.isNotBlank() && state.value.stagingCoverPath != null) {
            imageStagingRepository.requestSeriesCoverStagingCleanup(seriesId)
        }
        _navActions.trySend(SeriesEditNavAction.NavigateBack)
    }

    /**
     * Clean up staging files when ViewModel is destroyed.
     * Handles cases where user navigates away without explicitly canceling or saving.
     */
    override fun onCleared() {
        super.onCleared()
        val seriesId = state.value.seriesId
        if (seriesId.isNotBlank() && state.value.stagingCoverPath != null) {
            imageStagingRepository.requestSeriesCoverStagingCleanup(seriesId)
        }
    }
}
