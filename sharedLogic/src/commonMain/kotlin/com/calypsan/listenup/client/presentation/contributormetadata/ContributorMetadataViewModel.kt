package com.calypsan.listenup.client.presentation.contributormetadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataRequest
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.MetadataFieldSelections
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Tracks which contributor metadata fields the user has selected to apply.
 */
data class ContributorMetadataSelections(
    val name: Boolean = true,
    val biography: Boolean = true,
    val image: Boolean = true,
)

/**
 * Toggleable fields for contributor metadata.
 */
enum class ContributorMetadataField {
    NAME,
    BIOGRAPHY,
    IMAGE,
}

/**
 * UI state for the contributor metadata search and match flow.
 */
data class ContributorMetadataUiState(
    // Contributor context
    val contributorId: String = "",
    val currentContributor: Contributor? = null,
    // Region selection
    val selectedRegion: MetadataLocale = MetadataLocale.DEFAULT,
    // Search state
    val searchQuery: String = "",
    val searchResults: List<MetadataContributorHit> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    // Preview state
    val selectedCandidate: MetadataContributorHit? = null,
    val previewProfile: MetadataContributorProfile? = null,
    val isLoadingPreview: Boolean = false,
    val previewError: String? = null,
    // Field selections (for UI preview)
    val selections: ContributorMetadataSelections = ContributorMetadataSelections(),
    // Apply state
    val isApplying: Boolean = false,
    val applySuccess: Boolean = false,
    val applyError: String? = null,
)

/**
 * ViewModel for the contributor metadata search and match flow.
 *
 * Manages the full flow:
 * 1. Search Audible for contributor matches
 * 2. Select a match and preview the changes
 * 3. Apply the match to update the contributor via server RPC
 *
 * Uses `:contract` DTOs ([MetadataContributorHit], [MetadataContributorProfile])
 * directly — no parallel domain-DTO hierarchy (B2b "nuke legacy DTOs" decision).
 *
 * Note: [MetadataContributorProfile.description] corresponds to the biography
 * field displayed in the UI; the legacy domain type used `biography` as the
 * field name. UI references `previewProfile.description` now.
 */
class ContributorMetadataViewModel(
    private val contributorRepository: ContributorRepository,
    private val metadataRepository: MetadataRepository,
    private val applyContributorMetadataUseCase: ApplyContributorMetadataUseCase,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<ContributorMetadataUiState>
        field = MutableStateFlow(ContributorMetadataUiState())

    /**
     * Initialize the ViewModel for a specific contributor.
     *
     * Performs a synchronous state reset first to prevent stale state from being
     * visible during navigation transitions, then loads contributor data async.
     */
    fun init(contributorId: String) {
        // Synchronous reset — prevents stale state (e.g., applySuccess=true from
        // a previous contributor) from triggering side effects before async load
        state.value = ContributorMetadataUiState(contributorId = contributorId)

        viewModelScope.launch {
            // Load current contributor
            val contributor = contributorRepository.observeById(contributorId).first()

            state.update {
                it.copy(
                    currentContributor = contributor,
                    searchQuery = contributor?.name ?: "",
                )
            }

            // Auto-search with the contributor's name
            if (!contributor?.name.isNullOrBlank()) {
                search()
            }
        }
    }

    /**
     * Update the search query.
     */
    fun updateQuery(query: String) {
        state.update { it.copy(searchQuery = query) }
    }

    /**
     * Change the Audible region and re-search.
     */
    fun changeRegion(region: MetadataLocale) {
        state.update { it.copy(selectedRegion = region) }

        // Re-search with new region if we have a query
        if (state.value.searchQuery.isNotBlank()) {
            search()
        }
    }

    /**
     * Execute an Audible search with the current query.
     *
     * Note: [MetadataRepository.searchContributorMetadata] currently stubs to
     * an empty list (no Audible contributor-search API in the contract yet —
     * see MetadataLookupService KDoc). Results will appear transparently when
     * the server implementation lands.
     */
    fun search() {
        val query = state.value.searchQuery.trim()
        if (query.isBlank()) return

        viewModelScope.launch {
            state.update {
                it.copy(
                    isSearching = true,
                    searchError = null,
                )
            }

            when (val result = metadataRepository.searchContributorMetadata(query)) {
                is AppResult.Success -> {
                    logger.debug { "Contributor search for '$query' returned ${result.data.size} results" }
                    state.update {
                        it.copy(
                            searchResults = result.data,
                            isSearching = false,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Contributor metadata search failed: ${result.error.message}" }
                    state.update {
                        it.copy(
                            isSearching = false,
                            searchError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Select a candidate from search results and load its full profile.
     */
    fun selectCandidate(result: MetadataContributorHit) {
        state.update {
            it.copy(
                selectedCandidate = result,
                isLoadingPreview = true,
                previewProfile = null,
                previewError = null,
            )
        }

        viewModelScope.launch {
            when (
                val profileResult =
                    metadataRepository.getContributorMetadata(result.asin, state.value.selectedRegion)
            ) {
                is AppResult.Success -> {
                    val profile = profileResult.data
                    if (profile != null) {
                        logger.debug { "Loaded contributor profile for ${result.asin}: ${profile.name}" }
                        state.update {
                            it.copy(
                                previewProfile = profile,
                                isLoadingPreview = false,
                                selections = initializeSelections(profile),
                            )
                        }
                    } else {
                        logger.debug { "No contributor profile found for ${result.asin}" }
                        state.update {
                            it.copy(
                                isLoadingPreview = false,
                                previewError = "No profile found on Audible.",
                            )
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(profileResult.error)
                    logger.error { "Failed to load contributor profile: ${profileResult.error.message}" }
                    state.update {
                        it.copy(
                            isLoadingPreview = false,
                            previewError = profileResult.error.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Load a profile directly by ASIN (for returning to preview from search).
     */
    fun loadProfileByAsin(asin: String) {
        state.update {
            it.copy(
                selectedCandidate = MetadataContributorHit(asin = asin, name = ""),
                isLoadingPreview = true,
                previewProfile = null,
                previewError = null,
            )
        }

        viewModelScope.launch {
            when (
                val profileResult =
                    metadataRepository.getContributorMetadata(asin, state.value.selectedRegion)
            ) {
                is AppResult.Success -> {
                    val profile = profileResult.data
                    if (profile != null) {
                        logger.debug { "Loaded contributor profile by ASIN $asin: ${profile.name}" }
                        state.update {
                            it.copy(
                                selectedCandidate =
                                    MetadataContributorHit(
                                        asin = asin,
                                        name = profile.name,
                                    ),
                                previewProfile = profile,
                                isLoadingPreview = false,
                                selections = initializeSelections(profile),
                            )
                        }
                    } else {
                        state.update {
                            it.copy(
                                isLoadingPreview = false,
                                previewError = "No profile found on Audible.",
                            )
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(profileResult.error)
                    logger.error { "Failed to load contributor profile by ASIN: ${profileResult.error.message}" }
                    state.update {
                        it.copy(
                            isLoadingPreview = false,
                            previewError = profileResult.error.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Clear selection and return to search results.
     */
    fun clearSelection() {
        state.update {
            it.copy(
                selectedCandidate = null,
                previewProfile = null,
                previewError = null,
            )
        }
    }

    /**
     * Toggle a field selection.
     */
    fun toggleField(field: ContributorMetadataField) {
        state.update { currentState ->
            val selections = currentState.selections
            val newSelections =
                when (field) {
                    ContributorMetadataField.NAME -> selections.copy(name = !selections.name)
                    ContributorMetadataField.BIOGRAPHY -> selections.copy(biography = !selections.biography)
                    ContributorMetadataField.IMAGE -> selections.copy(image = !selections.image)
                }
            currentState.copy(selections = newSelections)
        }
    }

    /**
     * Check if any field is selected.
     */
    fun hasSelectedFields(): Boolean {
        val selections = state.value.selections
        return selections.name || selections.biography || selections.image
    }

    /**
     * Apply the selected metadata to the contributor via server RPC.
     *
     * Delegates to [ApplyContributorMetadataUseCase], which calls
     * [MetadataRepository.applyContributorMetadata]. The server enriches the
     * contributor entity and emits an SSE event; the updated contributor arrives
     * in Room automatically — no explicit local update is needed here.
     */
    fun apply() {
        val currentState = state.value
        val candidate = currentState.selectedCandidate ?: return

        if (!hasSelectedFields()) return

        viewModelScope.launch {
            state.update {
                it.copy(
                    isApplying = true,
                    applyError = null,
                )
            }

            val selections = currentState.selections
            val request =
                ApplyContributorMetadataRequest(
                    contributorId = currentState.contributorId,
                    asin = candidate.asin,
                    region = currentState.selectedRegion,
                    selections =
                        MetadataFieldSelections(
                            name = selections.name,
                            biography = selections.biography,
                            image = selections.image,
                        ),
                )

            when (val result = applyContributorMetadataUseCase(request)) {
                is AppResult.Success -> {
                    state.update {
                        it.copy(
                            isApplying = false,
                            applySuccess = true,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    state.update {
                        it.copy(
                            isApplying = false,
                            applyError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Reset all state.
     */
    fun reset() {
        state.update { ContributorMetadataUiState() }
    }

    /**
     * Initialize selections based on available profile data.
     *
     * Note: [MetadataContributorProfile.description] is the biography field.
     */
    private fun initializeSelections(profile: MetadataContributorProfile): ContributorMetadataSelections =
        ContributorMetadataSelections(
            name = profile.name.isNotBlank(),
            biography = !profile.description.isNullOrBlank(),
            image = !profile.imageUrl.isNullOrBlank(),
        )
}
