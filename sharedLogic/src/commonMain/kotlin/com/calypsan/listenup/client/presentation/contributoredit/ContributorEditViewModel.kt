package com.calypsan.listenup.client.presentation.contributoredit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.usecase.contributor.ContributorUpdateRequest
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
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
 * UI state for contributor editing screen.
 */
data class ContributorEditUiState(
    // Loading states
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isUploadingImage: Boolean = false,
    val error: String? = null,
    // Contributor identity
    val contributorId: String = "",
    val imagePath: String? = null,
    // Editable fields
    val name: String = "",
    val description: String = "",
    val website: String = "",
    val birthDate: String = "", // ISO 8601 format (YYYY-MM-DD)
    val deathDate: String = "", // ISO 8601 format (YYYY-MM-DD)
    // Track if changes have been made
    val hasChanges: Boolean = false,
)

/**
 * Events from the contributor edit UI.
 *
 * Alias-related events (search/select/enter/remove) were deleted with
 * Books-C1's removal of client-side merge/unmerge. TODO(books-c2): re-wire
 * alias management when server-canonical merge ships.
 */
sealed interface ContributorEditUiEvent {
    // Field changes

    /** User edited the contributor's name. */
    data class NameChanged(
        val name: String,
    ) : ContributorEditUiEvent

    /** User edited the biography/description. */
    data class DescriptionChanged(
        val description: String,
    ) : ContributorEditUiEvent

    /** User edited the website URL. */
    data class WebsiteChanged(
        val website: String,
    ) : ContributorEditUiEvent

    /** User changed the birth date (ISO 8601 `YYYY-MM-DD`, empty string clears). */
    data class BirthDateChanged(
        val date: String,
    ) : ContributorEditUiEvent

    /** User changed the death date (ISO 8601 `YYYY-MM-DD`, empty string clears). */
    data class DeathDateChanged(
        val date: String,
    ) : ContributorEditUiEvent

    // Image upload

    /** User chose an image for the contributor; uploaded immediately and saved locally. */
    data class UploadImage(
        val imageData: ByteArray,
        val filename: String,
    ) : ContributorEditUiEvent

    // Actions
    data object Save : ContributorEditUiEvent

    data object Cancel : ContributorEditUiEvent

    data object DismissError : ContributorEditUiEvent
}

/**
 * Navigation actions from contributor edit screen.
 */
sealed interface ContributorEditNavAction {
    data object NavigateBack : ContributorEditNavAction

    data object SaveSuccess : ContributorEditNavAction
}

/**
 * ViewModel for the contributor edit screen.
 *
 * Handles:
 * - Loading contributor data for editing
 * - Image upload via [ImageRepository]
 * - Saving metadata changes via [UpdateContributorUseCase] (RPC-backed)
 * - Tracking unsaved changes
 *
 * @property contributorRepository Repository for contributor data
 * @property updateContributorUseCase Use case for updating contributor metadata
 * @property imageRepository Repository for image operations
 */
class ContributorEditViewModel(
    private val contributorRepository: ContributorRepository,
    private val updateContributorUseCase: UpdateContributorUseCase,
    private val imageRepository: ImageRepository,
) : ViewModel() {
    val state: StateFlow<ContributorEditUiState>
        field = MutableStateFlow(ContributorEditUiState())

    private val _navActions = Channel<ContributorEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<ContributorEditNavAction> = _navActions.receiveAsFlow()

    // Track original values for change detection
    private var originalName: String = ""
    private var originalDescription: String = ""
    private var originalWebsite: String = ""
    private var originalBirthDate: String = ""
    private var originalDeathDate: String = ""
    private var originalImagePath: String? = null

    /**
     * Load contributor data for editing.
     */
    fun loadContributor(contributorId: String) {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true, contributorId = contributorId) }

            val contributor = contributorRepository.getById(contributorId)
            if (contributor == null) {
                state.update { it.copy(isLoading = false, error = "Contributor not found") }
                return@launch
            }

            // Store original values
            originalName = contributor.name
            originalDescription = contributor.description ?: ""
            originalWebsite = contributor.website ?: ""
            originalBirthDate = contributor.birthDate ?: ""
            originalDeathDate = contributor.deathDate ?: ""
            originalImagePath = contributor.imagePath

            state.update {
                it.copy(
                    isLoading = false,
                    imagePath = contributor.imagePath,
                    name = contributor.name,
                    description = contributor.description ?: "",
                    website = contributor.website ?: "",
                    birthDate = contributor.birthDate ?: "",
                    deathDate = contributor.deathDate ?: "",
                    hasChanges = false,
                )
            }

            logger.debug { "Loaded contributor for editing: ${contributor.name}" }
        }
    }

    /**
     * Handle UI events.
     */
    fun onEvent(event: ContributorEditUiEvent) {
        when (event) {
            is ContributorEditUiEvent.NameChanged -> {
                state.update { it.copy(name = event.name) }
                updateHasChanges()
            }

            is ContributorEditUiEvent.DescriptionChanged -> {
                state.update { it.copy(description = event.description) }
                updateHasChanges()
            }

            is ContributorEditUiEvent.WebsiteChanged -> {
                state.update { it.copy(website = event.website) }
                updateHasChanges()
            }

            is ContributorEditUiEvent.BirthDateChanged -> {
                state.update { it.copy(birthDate = event.date) }
                updateHasChanges()
            }

            is ContributorEditUiEvent.DeathDateChanged -> {
                state.update { it.copy(deathDate = event.date) }
                updateHasChanges()
            }

            is ContributorEditUiEvent.UploadImage -> {
                uploadImage(event.imageData, event.filename)
            }

            is ContributorEditUiEvent.Save -> {
                saveChanges()
            }

            is ContributorEditUiEvent.Cancel -> {
                _navActions.trySend(ContributorEditNavAction.NavigateBack)
            }

            is ContributorEditUiEvent.DismissError -> {
                state.update { it.copy(error = null) }
            }
        }
    }

    // ========== Image Upload ==========

    private fun uploadImage(
        imageData: ByteArray,
        filename: String,
    ) {
        val contributorId = state.value.contributorId
        if (contributorId.isBlank()) {
            logger.error { "Cannot upload image: contributor ID is empty" }
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isUploadingImage = true, error = null) }

            when (val result = imageRepository.uploadContributorImage(contributorId, imageData, filename)) {
                is Success -> {
                    logger.info { "Contributor image uploaded successfully to server" }

                    // Save image locally for offline-first access
                    when (val saveResult = imageRepository.saveContributorImage(contributorId, imageData)) {
                        is Success -> {
                            val localPath = imageRepository.getContributorImagePath(contributorId)
                            logger.info { "Contributor image saved locally: $localPath" }
                            state.update {
                                it.copy(
                                    isUploadingImage = false,
                                    imagePath = localPath,
                                )
                            }
                            updateHasChanges()
                        }

                        is Failure -> {
                            logger.error { "Failed to save contributor image locally: ${saveResult.message}" }
                            // Still mark upload as successful since server has the image
                            state.update {
                                it.copy(
                                    isUploadingImage = false,
                                    error = "Image uploaded but failed to save locally",
                                )
                            }
                        }
                    }
                }

                is Failure -> {
                    logger.error { "Failed to upload contributor image: ${result.message}" }
                    state.update {
                        it.copy(
                            isUploadingImage = false,
                            error = "Failed to upload image: ${result.message}",
                        )
                    }
                }
            }
        }
    }

    private fun updateHasChanges() {
        val current = state.value
        val hasChanges =
            current.name != originalName ||
                current.description != originalDescription ||
                current.website != originalWebsite ||
                current.birthDate != originalBirthDate ||
                current.deathDate != originalDeathDate ||
                current.imagePath != originalImagePath

        state.update { it.copy(hasChanges = hasChanges) }
    }

    /**
     * Save all changes via the use case.
     */
    private fun saveChanges() {
        val current = state.value
        if (!current.hasChanges) {
            _navActions.trySend(ContributorEditNavAction.NavigateBack)
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isSaving = true, error = null) }

            val result =
                updateContributorUseCase(
                    ContributorUpdateRequest(
                        contributorId = current.contributorId,
                        name = current.name,
                        biography = current.description,
                        website = current.website,
                        birthDate = current.birthDate,
                        deathDate = current.deathDate,
                    ),
                )

            when (result) {
                is Success -> {
                    state.update { it.copy(isSaving = false, hasChanges = false) }
                    _navActions.trySend(ContributorEditNavAction.SaveSuccess)
                }

                is Failure -> {
                    logger.error { "Failed to save contributor: ${result.message}" }
                    state.update {
                        it.copy(
                            isSaving = false,
                            error = "Failed to save: ${result.message}",
                        )
                    }
                }
            }
        }
    }
}
