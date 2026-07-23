package com.calypsan.listenup.client.presentation.contributoredit

import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.client.data.local.db.ContributorAliasDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.usecase.contributor.ContributorUpdateRequest
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
 * Lightweight projection of a contributor as a merge-target candidate.
 *
 * Used by [ContributorEditViewModel.mergeCandidates] to populate the contributor
 * merge picker dialog. [bookCount] is a placeholder (always `0`) — there is no
 * per-contributor book-count query yet, so the dialog hides it.
 */
data class ContributorCandidate(
    val id: ContributorId,
    val displayName: String,
    val bookCount: Int,
)

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
    // Pending image upload (staged locally until Save)
    val pendingImageData: ByteArray? = null,
    val pendingImageFilename: String? = null,
    // Staging image path for preview (separate from the main image)
    val stagingImagePath: String? = null,
    // Editable fields
    val name: String = "",
    val description: String = "",
    val website: String = "",
    val birthDate: String = "", // ISO 8601 format (YYYY-MM-DD)
    val deathDate: String = "", // ISO 8601 format (YYYY-MM-DD)
    // Alias management (Room-observed; updated reactively by the firehose)
    val aliases: List<String> = emptyList(),
    val mergeInProgress: Boolean = false,
    // Merge-target picker query (drives candidate filtering)
    val mergeQuery: String = "",
    // Track if changes have been made
    val hasChanges: Boolean = false,
) {
    /**
     * Returns the image path to display - staging if available, otherwise the original.
     */
    val displayImagePath: String?
        get() = stagingImagePath ?: imagePath
}

/**
 * Events from the contributor edit UI.
 *
 * Merge/unmerge are server-canonical operations (Books-C2): the VM dispatches
 * the RPC; the server emits sync events with authoritative state; the Room aliases
 * Flow re-emits without optimistic local writes.
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

    /** User chose an image for the contributor; bytes are staged for preview until Save. */
    data class UploadImage(
        val imageData: ByteArray,
        val filename: String,
    ) : ContributorEditUiEvent

    // Actions
    data object Save : ContributorEditUiEvent

    data object Cancel : ContributorEditUiEvent

    data object DismissError : ContributorEditUiEvent

    /**
     * User picked [targetId] in the alias dialog to fold INTO the contributor being edited: the
     * edited (viewed) contributor stays canonical and gains [targetId]'s name as an alias, while
     * [targetId] itself is soft-deleted.
     */
    data class MergeInto(
        val targetId: ContributorId,
    ) : ContributorEditUiEvent

    /** User chose to split [aliasName] back out into its own contributor. */
    data class UnmergeAlias(
        val aliasName: String,
    ) : ContributorEditUiEvent
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
 * - Server-canonical merge/unmerge via [ContributorEditRepository]
 * - Reactive alias display via [ContributorAliasDao] Room observation
 * - Tracking unsaved changes
 *
 * @property contributorRepository Repository for contributor data
 * @property updateContributorUseCase Use case for updating contributor metadata
 * @property imageRepository Repository for persistent image operations (upload, local save)
 * @property imageStagingRepository Repository for staging image operations (preview before save)
 * @property contributorEditRepository RPC dispatcher for merge/unmerge
 * @property contributorAliasDao DAO for observing the contributor's aliases (Room is read truth)
 * @property contributorDao DAO for browsing all contributors as merge-target candidates
 * @property errorBus Global error bus for snackbar emissions
 */
class ContributorEditViewModel internal constructor(
    private val contributorRepository: ContributorRepository,
    private val updateContributorUseCase: UpdateContributorUseCase,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
    private val contributorEditRepository: ContributorEditRepository,
    private val contributorAliasDao: ContributorAliasDao,
    private val contributorDao: ContributorDao,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<ContributorEditUiState>
        field = MutableStateFlow(ContributorEditUiState())

    private val _navActions = Channel<ContributorEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<ContributorEditNavAction> = _navActions.receiveAsFlow()

    /**
     * Candidates for the merge-target picker — all live contributors except the current
     * one, filtered by [ContributorEditUiState.mergeQuery] (case-insensitive substring).
     * Capped at [MAX_MERGE_CANDIDATES] to keep the dialog snappy.
     */
    val mergeCandidates: StateFlow<List<ContributorCandidate>> =
        combine(state, contributorDao.observeAll()) { uiState, allContributors ->
            val currentId = uiState.contributorId
            val query = uiState.mergeQuery
            allContributors
                .asSequence()
                .filter { it.deletedAt == null }
                .filter { it.id.value != currentId }
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name.lowercase() }
                .take(MAX_MERGE_CANDIDATES)
                .map { entity ->
                    ContributorCandidate(
                        id = entity.id,
                        displayName = entity.name,
                        bookCount = 0,
                    )
                }.toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    // Track original values for change detection
    private var originalName: String = ""
    private var originalDescription: String = ""
    private var originalWebsite: String = ""
    private var originalBirthDate: String = ""
    private var originalDeathDate: String = ""

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

            // Subscribe to Room aliases (single source of truth; updated by the firehose).
            contributorAliasDao
                .observeForContributor(contributorId)
                .onEach { aliases -> state.update { it.copy(aliases = aliases) } }
                .launchIn(viewModelScope)

            logger.debug { "Loaded contributor for editing: ${contributor.name}" }
        }
    }

    /**
     * Update the merge-target picker's search query. The [mergeCandidates] Flow
     * re-emits a filtered list whenever this changes.
     */
    fun onMergeQueryChange(query: String) {
        state.update { it.copy(mergeQuery = query) }
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
                stageImage(event.imageData, event.filename)
            }

            is ContributorEditUiEvent.Save -> {
                saveChanges()
            }

            is ContributorEditUiEvent.Cancel -> {
                cancelAndCleanup()
            }

            is ContributorEditUiEvent.DismissError -> {
                state.update { it.copy(error = null) }
            }

            is ContributorEditUiEvent.MergeInto -> {
                mergeInto(event.targetId)
            }

            is ContributorEditUiEvent.UnmergeAlias -> {
                unmergeAlias(event.aliasName)
            }
        }
    }

    // ========== Merge / Unmerge ==========

    /**
     * Fold the [chosen] contributor into the one whose page we're on. The **viewed** contributor is
     * canonical: it is the merge *target* (it survives and gains the chosen contributor's name as an
     * alias), while [chosen] is the merge *source* (soft-deleted). This matches the user's mental
     * model — "on J.K. Rowling's page, add Robert Galbraith as an alias" keeps Rowling and folds
     * Galbraith in. Passing these in the other order would delete the page you're viewing.
     */
    private fun mergeInto(chosen: ContributorId) {
        val viewedId = state.value.contributorId
        if (viewedId.isBlank()) {
            logger.error { "Cannot merge: contributor ID is empty" }
            return
        }

        viewModelScope.launch {
            state.update { it.copy(mergeInProgress = true, error = null) }

            when (
                val result =
                    contributorEditRepository.mergeContributor(
                        source = chosen,
                        target = ContributorId(viewedId),
                    )
            ) {
                is AppResult.Success -> {
                    state.update { it.copy(mergeInProgress = false) }
                    _navActions.trySend(ContributorEditNavAction.NavigateBack)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to merge contributor: ${result.message}" }
                    state.update {
                        it.copy(
                            mergeInProgress = false,
                            error =
                                when (result.error) {
                                    is ContributorError.MergeSelfTarget -> "Can't merge a contributor with itself."
                                    is ContributorError.NotFound -> "One of these contributors no longer exists."
                                    else -> result.error.message
                                },
                        )
                    }
                }
            }
        }
    }

    /**
     * Split [aliasName] back into its own contributor. The aliases Room Flow re-emits
     * without [aliasName] once the firehose event lands; we stay on this screen.
     */
    private fun unmergeAlias(aliasName: String) {
        val contributorId = state.value.contributorId
        if (contributorId.isBlank()) {
            logger.error { "Cannot unmerge: contributor ID is empty" }
            return
        }

        viewModelScope.launch {
            when (val result = contributorEditRepository.unmergeContributor(ContributorId(contributorId), aliasName)) {
                is AppResult.Success -> {
                    logger.debug { "Unmerged alias '$aliasName'; new contributor id=${result.data}" }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to unmerge alias '$aliasName': ${result.message}" }
                    state.update {
                        it.copy(
                            error =
                                when (result.error) {
                                    is ContributorError.AliasNotFound -> "That alias is no longer on this contributor."
                                    is ContributorError.NotFound -> "This contributor no longer exists."
                                    else -> result.error.message
                                },
                        )
                    }
                }
            }
        }
    }

    // ========== Image Staging ==========

    /**
     * Handle image selection.
     * Saves the image to a staging location for preview.
     * Does NOT upload to the server or overwrite the main image until saveChanges() is called.
     */
    private fun stageImage(
        imageData: ByteArray,
        filename: String,
    ) {
        val contributorId = state.value.contributorId
        if (contributorId.isBlank()) {
            logger.error { "Cannot set image: contributor ID is empty" }
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isUploadingImage = true, error = null) }

            // Save to staging location for preview (doesn't upload or overwrite the original)
            when (val saveResult = imageStagingRepository.saveContributorImageStaging(contributorId, imageData)) {
                is AppResult.Success -> {
                    val stagingPath = imageStagingRepository.getContributorImageStagingPath(contributorId)
                    logger.info { "Contributor image saved to staging for preview: $stagingPath" }

                    // Store pending data for upload when Save is clicked
                    state.update {
                        it.copy(
                            isUploadingImage = false,
                            stagingImagePath = stagingPath,
                            pendingImageData = imageData,
                            pendingImageFilename = filename,
                        )
                    }
                    updateHasChanges()
                }

                is AppResult.Failure -> {
                    errorBus.emit(saveResult.error)
                    logger.error { "Failed to save contributor image to staging: ${saveResult.message}" }
                    state.update {
                        it.copy(
                            isUploadingImage = false,
                            error = "Failed to save image: ${saveResult.message}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Commit the staged image to the durable local location and upload it to the server.
     * Image upload is best-effort — the local save is prioritized so the picked photo
     * survives offline. Called from [saveChanges] once metadata persists.
     */
    private suspend fun commitAndUploadImageIfPending(current: ContributorEditUiState) {
        val pendingData = current.pendingImageData ?: return
        val pendingFilename = current.pendingImageFilename ?: return
        val contributorId = current.contributorId

        // First, commit staging to the main image location (offline-first).
        when (val commitResult = imageStagingRepository.commitContributorImageStaging(contributorId)) {
            is AppResult.Success -> logger.info { "Staging image committed to main location" }
            is AppResult.Failure -> logger.error { "Failed to commit staging image: ${commitResult.message}" }
        }

        // Then upload to the server (best-effort — local image is already saved).
        when (val result = imageRepository.uploadContributorImage(contributorId, pendingData, pendingFilename)) {
            is AppResult.Success -> {
                logger.info { "Contributor image uploaded to server" }
            }

            is AppResult.Failure -> {
                logger.error { "Failed to upload contributor image: ${result.message}" }
                logger.warn { "Continuing despite image upload failure (local image saved)" }
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
                current.pendingImageData != null // Image changed if we have pending staged data

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
                is AppResult.Success -> {
                    // Metadata persisted — commit + upload the staged image if the user picked one.
                    commitAndUploadImageIfPending(current)
                    state.update {
                        it.copy(
                            isSaving = false,
                            hasChanges = false,
                            pendingImageData = null,
                            pendingImageFilename = null,
                            stagingImagePath = null,
                        )
                    }
                    _navActions.trySend(ContributorEditNavAction.SaveSuccess)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
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

    /**
     * Cancel editing and clean up any staged image file, then navigate back.
     */
    private fun cancelAndCleanup() {
        val contributorId = state.value.contributorId
        if (contributorId.isNotBlank() && state.value.stagingImagePath != null) {
            imageStagingRepository.requestContributorImageStagingCleanup(contributorId)
        }
        _navActions.trySend(ContributorEditNavAction.NavigateBack)
    }

    /**
     * Clean up staging files when the ViewModel is destroyed.
     * Handles cases where the user navigates away without explicitly canceling or saving.
     */
    override fun onCleared() {
        super.onCleared()
        val contributorId = state.value.contributorId
        if (contributorId.isNotBlank() && state.value.stagingImagePath != null) {
            imageStagingRepository.requestContributorImageStagingCleanup(contributorId)
        }
    }
}
