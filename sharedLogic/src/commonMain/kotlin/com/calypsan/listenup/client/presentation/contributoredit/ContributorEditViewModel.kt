@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.presentation.contributoredit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.usecase.contributor.ContributorUpdateRequest
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    // Aliases - pen names merged into this contributor
    val aliases: List<String> = emptyList(),
    val aliasSearchQuery: String = "",
    val aliasSearchResults: List<ContributorSearchResult> = emptyList(),
    val aliasSearchLoading: Boolean = false,
    // Track if changes have been made
    val hasChanges: Boolean = false,
)

/**
 * Events from the contributor edit UI.
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

    // Alias management

    /** User typed in the alias-search box. */
    data class AliasSearchQueryChanged(
        val query: String,
    ) : ContributorEditUiEvent

    /** User picked an existing contributor to merge in as an alias of the one being edited. */
    data class AliasSelected(
        val result: ContributorSearchResult,
    ) : ContributorEditUiEvent

    /** User typed a free-form alias name (no existing contributor to merge — pen name). */
    data class AliasEntered(
        val name: String,
    ) : ContributorEditUiEvent

    /** User detached an alias. If it was an original alias, this triggers an unmerge; if newly added, it just drops from the list. */
    data class RemoveAlias(
        val alias: String,
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
 * - Managing aliases with merge logic (absorbing other contributors)
 * - Saving changes via offline-first repository
 * - Tracking unsaved changes
 *
 * Alias Merge Flow (offline-first):
 * When user adds "Richard Bachman" as an alias to Stephen King:
 * 1. If a ContributorEntity named "Richard Bachman" exists:
 *    - Calls ContributorEditRepository.mergeContributor()
 *    - Repository re-links book relationships and deletes source locally
 *    - Operation is queued for server sync
 * 2. Alias is added to the UI state
 *
 * @property contributorRepository Repository for contributor data and search
 * @property contributorEditRepository Repository for editing (offline-first)
 * @property updateContributorUseCase Use case for updating contributor
 * @property imageRepository Repository for image operations
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ContributorEditViewModel(
    private val contributorRepository: ContributorRepository,
    private val contributorEditRepository: ContributorEditRepository,
    private val updateContributorUseCase: UpdateContributorUseCase,
    private val imageRepository: ImageRepository,
) : ViewModel() {
    val state: StateFlow<ContributorEditUiState>
        field = MutableStateFlow(ContributorEditUiState())

    private val _navActions = Channel<ContributorEditNavAction>(Channel.BUFFERED)
    val navActions: Flow<ContributorEditNavAction> = _navActions.receiveAsFlow()

    // Alias search - flatMapLatest handles cancellation automatically
    private val aliasQueryFlow = MutableStateFlow("")

    // Track original values for change detection
    private var originalName: String = ""
    private var originalDescription: String = ""
    private var originalWebsite: String = ""
    private var originalBirthDate: String = ""
    private var originalDeathDate: String = ""
    private var originalAliases: List<String> = emptyList()
    private var originalImagePath: String? = null

    // Track contributors to merge on save (selected from autocomplete)
    private val contributorsToMerge = mutableMapOf<String, ContributorSearchResult>()

    init {
        setupAliasSearch()
    }

    /**
     * Internal result type for the reactive alias search flow.
     */
    private sealed interface AliasSearchFlowResult {
        data object Empty : AliasSearchFlowResult

        data object Loading : AliasSearchFlowResult

        /** Search succeeded; [results] is filtered to exclude the current contributor and existing aliases. */
        data class Success(
            val results: List<ContributorSearchResult>,
        ) : AliasSearchFlowResult
    }

    /**
     * Set up debounced search for aliases with automatic cancellation via flatMapLatest.
     */
    private fun setupAliasSearch() {
        aliasQueryFlow
            .debounce(300)
            .distinctUntilChanged()
            .filter { it.length >= 2 || it.isEmpty() }
            .flatMapLatest { query ->
                if (query.isBlank()) {
                    flowOf<AliasSearchFlowResult>(AliasSearchFlowResult.Empty)
                } else {
                    flow<AliasSearchFlowResult> {
                        emit(AliasSearchFlowResult.Loading)
                        emit(performAliasSearch(query))
                    }
                }
            }.onEach { result ->
                when (result) {
                    is AliasSearchFlowResult.Empty -> {
                        state.update {
                            it.copy(aliasSearchResults = emptyList(), aliasSearchLoading = false)
                        }
                    }

                    is AliasSearchFlowResult.Loading -> {
                        state.update { it.copy(aliasSearchLoading = true) }
                    }

                    is AliasSearchFlowResult.Success -> {
                        state.update {
                            it.copy(aliasSearchResults = result.results, aliasSearchLoading = false)
                        }
                        logger.debug { "Alias search: ${result.results.size} results" }
                    }
                }
            }.launchIn(viewModelScope)
    }

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
            originalAliases = contributor.aliases
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
                    aliases = contributor.aliases,
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

            is ContributorEditUiEvent.AliasSearchQueryChanged -> {
                state.update { it.copy(aliasSearchQuery = event.query) }
                aliasQueryFlow.value = event.query
            }

            is ContributorEditUiEvent.AliasSelected -> {
                selectAlias(event.result)
            }

            is ContributorEditUiEvent.AliasEntered -> {
                addManualAlias(event.name)
            }

            is ContributorEditUiEvent.RemoveAlias -> {
                removeAlias(event.alias)
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

    /**
     * Perform alias search and return the result.
     * Called from flatMapLatest flow - cancellation is handled automatically.
     */
    private suspend fun performAliasSearch(query: String): AliasSearchFlowResult {
        val response = contributorRepository.searchContributors(query, limit = 10)

        // Filter out:
        // - The current contributor (can't be an alias of itself)
        // - Contributors already in aliases list
        val currentId = state.value.contributorId
        val currentAliases =
            state.value.aliases
                .map { it.lowercase() }
                .toSet()

        val filteredResults =
            response.contributors.filter { result ->
                result.id != currentId && result.name.lowercase() !in currentAliases
            }

        return AliasSearchFlowResult.Success(filteredResults)
    }

    /**
     * Add alias from autocomplete selection (existing contributor to merge).
     */
    private fun selectAlias(result: ContributorSearchResult) {
        val aliasName = result.name

        state.update { current ->
            // Check if already added
            if (current.aliases.any { it.equals(aliasName, ignoreCase = true) }) {
                return@update current.copy(
                    aliasSearchQuery = "",
                    aliasSearchResults = emptyList(),
                )
            }

            current.copy(
                aliases = current.aliases + aliasName,
                aliasSearchQuery = "",
                aliasSearchResults = emptyList(),
            )
        }

        // Track this contributor for merging on save
        contributorsToMerge[aliasName.lowercase()] = result

        aliasQueryFlow.value = ""
        updateHasChanges()
    }

    /**
     * Add alias from manual text entry (pen name that might not exist yet).
     */
    private fun addManualAlias(name: String) {
        if (name.isBlank()) return

        val trimmedName = name.trim()

        state.update { current ->
            // Check if already added
            if (current.aliases.any { it.equals(trimmedName, ignoreCase = true) }) {
                return@update current.copy(
                    aliasSearchQuery = "",
                    aliasSearchResults = emptyList(),
                )
            }

            current.copy(
                aliases = current.aliases + trimmedName,
                aliasSearchQuery = "",
                aliasSearchResults = emptyList(),
            )
        }

        aliasQueryFlow.value = ""
        updateHasChanges()
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

    /**
     * Remove an alias from the contributor.
     *
     * If this was an original alias (existed when loading), calls unmerge to split it out.
     * If it was newly added, just removes from the local list.
     */
    private fun removeAlias(alias: String) {
        val isOriginalAlias = originalAliases.any { it.equals(alias, ignoreCase = true) }

        if (isOriginalAlias) {
            // This was an existing alias - call unmerge via repository
            viewModelScope.launch {
                unmergeAlias(alias)
            }
        } else {
            // This was a newly added alias - just remove it from the list
            state.update { current ->
                current.copy(aliases = current.aliases.filter { !it.equals(alias, ignoreCase = true) })
            }
            // Remove from merge tracking
            contributorsToMerge.remove(alias.lowercase())
            updateHasChanges()
        }
    }

    /**
     * Unmerge an alias via the offline-first repository.
     */
    private suspend fun unmergeAlias(aliasName: String) {
        val contributorId = state.value.contributorId

        logger.info { "Unmerging alias '$aliasName' from contributor $contributorId" }

        state.update { it.copy(isSaving = true) }

        when (val result = contributorEditRepository.unmergeContributor(contributorId, aliasName)) {
            is Success -> {
                logger.info { "Unmerge queued successfully" }

                // Update local state to remove the alias
                state.update { current ->
                    current.copy(
                        aliases = current.aliases.filter { !it.equals(aliasName, ignoreCase = true) },
                        isSaving = false,
                    )
                }

                // Update original aliases list so we don't think it's still there
                originalAliases = originalAliases.filter { !it.equals(aliasName, ignoreCase = true) }

                updateHasChanges()
            }

            is Failure -> {
                (null as Exception?)?.let { logger.error(it) { "Unmerge failed for alias '$aliasName'" } }
                state.update {
                    it.copy(
                        isSaving = false,
                        error = "Failed to remove alias: ${result.message}",
                    )
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
                current.aliases.toSet() != originalAliases.toSet() ||
                current.imagePath != originalImagePath

        state.update { it.copy(hasChanges = hasChanges) }
    }

    /**
     * Save all changes via the use case.
     *
     * The use case orchestrates:
     * 1. Handle new aliases by merging contributors
     * 2. Update contributor metadata
     * 3. Changes are applied locally and queued for sync
     */
    private fun saveChanges() {
        val current = state.value
        if (!current.hasChanges) {
            _navActions.trySend(ContributorEditNavAction.NavigateBack)
            return
        }

        viewModelScope.launch {
            state.update { it.copy(isSaving = true, error = null) }

            val newAliases = current.aliases.toSet() - originalAliases.toSet()

            val result =
                updateContributorUseCase(
                    ContributorUpdateRequest(
                        contributorId = current.contributorId,
                        name = current.name,
                        biography = current.description,
                        website = current.website,
                        birthDate = current.birthDate,
                        deathDate = current.deathDate,
                        aliases = current.aliases,
                        newAliases = newAliases,
                        contributorsToMerge = contributorsToMerge.toMap(),
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
