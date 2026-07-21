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
)

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

    /** User chose an image for the contributor; uploaded immediately and saved locally. */
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
 * @property imageRepository Repository for image operations
 * @property contributorEditRepository RPC dispatcher for merge/unmerge
 * @property contributorAliasDao DAO for observing the contributor's aliases (Room is read truth)
 * @property contributorDao DAO for browsing all contributors as merge-target candidates
 * @property errorBus Global error bus for snackbar emissions
 */
class ContributorEditViewModel internal constructor(
    private val contributorRepository: ContributorRepository,
    private val updateContributorUseCase: UpdateContributorUseCase,
    private val imageRepository: ImageRepository,
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
                is AppResult.Success -> {
                    logger.info { "Contributor image uploaded successfully to server" }

                    // Save image locally for offline-first access
                    when (val saveResult = imageRepository.saveContributorImage(contributorId, imageData)) {
                        is AppResult.Success -> {
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

                        is AppResult.Failure -> {
                            errorBus.emit(saveResult.error)
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

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
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
                is AppResult.Success -> {
                    state.update { it.copy(isSaving = false, hasChanges = false) }
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
}
