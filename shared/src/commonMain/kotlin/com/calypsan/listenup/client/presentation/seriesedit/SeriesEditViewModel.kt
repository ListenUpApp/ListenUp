package com.calypsan.listenup.client.presentation.seriesedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.series.SeriesUpdateRequest
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
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
 * - Tracking unsaved changes
 *
 * @property seriesRepository Repository for loading series data
 * @property updateSeriesUseCase Use case for saving series changes
 * @property imageRepository Repository for persistent cover image operations
 * @property imageStagingRepository Repository for staging cover image operations
 */
class SeriesEditViewModel(
    private val seriesRepository: SeriesRepository,
    private val updateSeriesUseCase: UpdateSeriesUseCase,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
) : ViewModel() {
    val state: StateFlow<SeriesEditUiState>
        field = MutableStateFlow(SeriesEditUiState())

    private val _navActions = Channel<SeriesEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<SeriesEditNavAction> = _navActions.receiveAsFlow()

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
                is Success -> {
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

                is Failure -> {
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

        viewModelScope.launch {
            // Delete staging cover if it exists
            if (state.value.stagingCoverPath != null) {
                imageStagingRepository.deleteSeriesCoverStaging(seriesId)
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
                is Success -> {
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

                is Failure -> {
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
            viewModelScope.launch {
                imageStagingRepository.deleteSeriesCoverStaging(seriesId)
                logger.debug { "Staging cover cleaned up on cancel" }
            }
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
