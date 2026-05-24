package com.calypsan.listenup.client.presentation.contributormetadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorMetadataCandidate
import com.calypsan.listenup.client.domain.repository.ContributorMetadataProfile
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataRequest
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.MetadataFieldSelections
import com.calypsan.listenup.api.metadata.AudibleRegion
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
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
    val selectedRegion: AudibleRegion = AudibleRegion.US,
    // Search state
    val searchQuery: String = "",
    val searchResults: List<ContributorMetadataCandidate> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    // Preview state
    val selectedCandidate: ContributorMetadataCandidate? = null,
    val previewProfile: ContributorMetadataProfile? = null,
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
 * 3. Apply the match to update the contributor via server API
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
        // Synchronous reset - prevents stale state (e.g., applySuccess=true from
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
    fun changeRegion(region: AudibleRegion) {
        state.update { it.copy(selectedRegion = region) }

        // Re-search with new region if we have a query
        if (state.value.searchQuery.isNotBlank()) {
            search()
        }
    }

    /**
     * Execute an Audible search with the current query.
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

            try {
                val region = state.value.selectedRegion.code
                val results = metadataRepository.searchContributors(query, region)
                logger.debug { "Contributor search for '$query' in $region returned ${results.size} results" }

                state.update {
                    it.copy(
                        searchResults = results,
                        isSearching = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Contributor metadata search failed" }
                state.update {
                    it.copy(
                        isSearching = false,
                        searchError = e.message ?: "Search failed",
                    )
                }
            }
        }
    }

    /**
     * Select a candidate from search results and load its full profile.
     */
    fun selectCandidate(result: ContributorMetadataCandidate) {
        state.update {
            it.copy(
                selectedCandidate = result,
                isLoadingPreview = true,
                previewProfile = null,
                previewError = null,
            )
        }

        viewModelScope.launch {
            try {
                val profile = metadataRepository.getContributorProfile(result.asin)
                logger.debug { "Loaded contributor profile for ${result.asin}: ${profile.name}" }

                // Initialize selections based on available data
                val selections = initializeSelections(profile)

                state.update {
                    it.copy(
                        previewProfile = profile,
                        isLoadingPreview = false,
                        selections = selections,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to load contributor profile" }
                state.update {
                    it.copy(
                        isLoadingPreview = false,
                        previewError = e.message ?: "Failed to load profile",
                    )
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
                selectedCandidate =
                    ContributorMetadataCandidate(
                        asin = asin,
                        name = "",
                        imageUrl = null,
                        description = null,
                    ),
                isLoadingPreview = true,
                previewProfile = null,
                previewError = null,
            )
        }

        viewModelScope.launch {
            try {
                val profile = metadataRepository.getContributorProfile(asin)
                logger.debug { "Loaded contributor profile by ASIN $asin: ${profile.name}" }

                val selections = initializeSelections(profile)

                state.update {
                    it.copy(
                        selectedCandidate =
                            ContributorMetadataCandidate(
                                asin = asin,
                                name = profile.name,
                                imageUrl = profile.imageUrl,
                                description = null,
                            ),
                        previewProfile = profile,
                        isLoadingPreview = false,
                        selections = selections,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to load contributor profile by ASIN" }
                state.update {
                    it.copy(
                        isLoadingPreview = false,
                        previewError = e.message ?: "Failed to load profile",
                    )
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
     * Apply the selected metadata to the contributor via server API.
     *
     * Delegates to the use case which handles:
     * - API call to apply metadata
     * - Image download and local storage
     * - Database update
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
                    imageUrl = candidate.imageUrl,
                    selections =
                        MetadataFieldSelections(
                            name = selections.name,
                            biography = selections.biography,
                            image = selections.image,
                        ),
                )

            when (val result = applyContributorMetadataUseCase(request)) {
                is Success -> {
                    state.update {
                        it.copy(
                            isApplying = false,
                            applySuccess = true,
                            currentContributor = result.data,
                        )
                    }
                }

                is Failure -> {
                    state.update {
                        it.copy(
                            isApplying = false,
                            applyError = result.message,
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
     */
    private fun initializeSelections(profile: ContributorMetadataProfile): ContributorMetadataSelections =
        ContributorMetadataSelections(
            name = profile.name.isNotBlank(),
            biography = !profile.biography.isNullOrBlank(),
            image = !profile.imageUrl.isNullOrBlank(),
        )
}
