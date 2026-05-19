@file:OptIn(FlowPreview::class)

package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.bookedit.ContributorRole
import com.calypsan.listenup.client.presentation.bookedit.EditableContributor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val SEARCH_DEBOUNCE_MS = 300L
private const val MIN_QUERY_LENGTH = 2
private const val SEARCH_LIMIT = 10

/**
 * Delegate handling contributor editing operations.
 *
 * Responsibilities:
 * - Per-role debounced contributor search
 * - Add/remove contributors with role management
 * - Role section visibility control
 *
 * @property state Shared state flow owned by ViewModel
 * @property contributorRepository Repository for contributor search
 * @property scope CoroutineScope for launching operations
 * @property onChangesMade Callback to notify ViewModel of changes
 */
class ContributorEditDelegate(
    private val state: MutableStateFlow<BookEditUiState>,
    private val contributorRepository: ContributorRepository,
    private val scope: CoroutineScope,
    private val onChangesMade: () -> Unit,
) {
    // Per-role query flows for debounced search
    private val roleQueryFlows = mutableMapOf<ContributorRole, MutableStateFlow<String>>()
    private val roleSearchJobs = mutableMapOf<ContributorRole, Job>()

    /**
     * Set up debounced search for a specific role.
     * Called when a role section becomes visible.
     */
    fun setupRoleSearch(role: ContributorRole) {
        if (roleQueryFlows.containsKey(role)) return

        val queryFlow = MutableStateFlow("")
        roleQueryFlows[role] = queryFlow

        queryFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .filter { it.length >= MIN_QUERY_LENGTH || it.isEmpty() }
            .onEach { query ->
                if (query.isBlank()) {
                    state.update {
                        it.copy(
                            roleSearchResults = it.roleSearchResults - role,
                            roleSearchLoading = it.roleSearchLoading - role,
                        )
                    }
                } else {
                    performRoleSearch(role, query)
                }
            }.launchIn(scope)
    }

    /**
     * Update the search query for a role.
     */
    fun updateSearchQuery(
        role: ContributorRole,
        query: String,
    ) {
        state.update {
            it.copy(roleSearchQueries = it.roleSearchQueries + (role to query))
        }
        roleQueryFlows[role]?.value = query
    }

    /**
     * Clear search state for a role.
     */
    fun clearSearch(role: ContributorRole) {
        state.update {
            it.copy(
                roleSearchQueries = it.roleSearchQueries + (role to ""),
                roleSearchResults = it.roleSearchResults - role,
            )
        }
        roleQueryFlows[role]?.value = ""
    }

    /**
     * Add a role section to visible roles.
     */
    fun addRoleSection(role: ContributorRole) {
        setupRoleSearch(role)
        state.update {
            it.copy(visibleRoles = it.visibleRoles + role)
        }
    }

    /**
     * Add a contributor by selecting from search results.
     */
    fun selectContributor(
        role: ContributorRole,
        result: ContributorSearchResult,
    ) {
        state.update { current ->
            // Check if contributor already exists (by ID)
            val existing = current.contributors.find { it.id == result.id }

            if (existing != null) {
                // Check if already has this role (duplicate prevention)
                if (role in existing.roles) {
                    // Already has this role - just clear search
                    return@update current.copy(
                        roleSearchQueries = current.roleSearchQueries + (role to ""),
                        roleSearchResults = current.roleSearchResults - role,
                    )
                }
                // Add role to existing contributor
                val updated = existing.copy(roles = existing.roles + role)
                current.copy(
                    contributors =
                        current.contributors.map {
                            if (it == existing) updated else it
                        },
                    roleSearchQueries = current.roleSearchQueries + (role to ""),
                    roleSearchResults = current.roleSearchResults - role,
                )
            } else {
                // Add new contributor with this role
                val newContributor =
                    EditableContributor(
                        id = result.id,
                        name = result.name,
                        roles = setOf(role),
                    )
                current.copy(
                    contributors = current.contributors + newContributor,
                    roleSearchQueries = current.roleSearchQueries + (role to ""),
                    roleSearchResults = current.roleSearchResults - role,
                )
            }
        }

        roleQueryFlows[role]?.value = ""
        onChangesMade()
    }

    /**
     * Add a contributor by entering a new name.
     */
    fun addContributor(
        role: ContributorRole,
        name: String,
    ) {
        if (name.isBlank()) return

        val trimmedName = name.trim()

        state.update { current ->
            // Check if contributor already exists (by name)
            val existing =
                current.contributors.find {
                    it.name.equals(trimmedName, ignoreCase = true)
                }

            if (existing != null) {
                // Check if already has this role (duplicate prevention)
                if (role in existing.roles) {
                    // Already has this role - just clear search
                    return@update current.copy(
                        roleSearchQueries = current.roleSearchQueries + (role to ""),
                        roleSearchResults = current.roleSearchResults - role,
                    )
                }
                // Add role to existing contributor
                val updated = existing.copy(roles = existing.roles + role)
                current.copy(
                    contributors =
                        current.contributors.map {
                            if (it == existing) updated else it
                        },
                    roleSearchQueries = current.roleSearchQueries + (role to ""),
                    roleSearchResults = current.roleSearchResults - role,
                )
            } else {
                // Add new contributor with this role
                val newContributor =
                    EditableContributor(
                        id = null,
                        name = trimmedName,
                        roles = setOf(role),
                    )
                current.copy(
                    contributors = current.contributors + newContributor,
                    roleSearchQueries = current.roleSearchQueries + (role to ""),
                    roleSearchResults = current.roleSearchResults - role,
                )
            }
        }

        roleQueryFlows[role]?.value = ""
        onChangesMade()
    }

    /**
     * Remove a contributor from a specific role.
     * If the contributor has no remaining roles, they are removed entirely.
     */
    fun removeContributorFromRole(
        contributor: EditableContributor,
        role: ContributorRole,
    ) {
        state.update { current ->
            val updatedRoles = contributor.roles - role

            val updatedContributors =
                if (updatedRoles.isEmpty()) {
                    // Remove contributor entirely if no roles left
                    current.contributors - contributor
                } else {
                    // Just remove this role from the contributor
                    current.contributors.map {
                        if (it == contributor) it.copy(roles = updatedRoles) else it
                    }
                }

            // Check if any contributors still have this role
            val roleStillHasContributors = updatedContributors.any { role in it.roles }

            // Auto-hide section if empty (unless it's AUTHOR which always shows)
            val updatedVisibleRoles =
                if (!roleStillHasContributors && role != ContributorRole.AUTHOR) {
                    current.visibleRoles - role
                } else {
                    current.visibleRoles
                }

            current.copy(
                contributors = updatedContributors,
                visibleRoles = updatedVisibleRoles,
            )
        }
        onChangesMade()
    }

    /**
     * Remove an entire role section and all contributors from that role.
     */
    fun removeRoleSection(role: ContributorRole) {
        state.update { current ->
            // Remove role from all contributors
            val updatedContributors =
                current.contributors.mapNotNull { contributor ->
                    val updatedRoles = contributor.roles - role
                    if (updatedRoles.isEmpty()) {
                        null // Remove contributor entirely if no roles left
                    } else {
                        contributor.copy(roles = updatedRoles)
                    }
                }

            // Remove from visible roles and clean up search state
            current.copy(
                contributors = updatedContributors,
                visibleRoles = current.visibleRoles - role,
                roleSearchQueries = current.roleSearchQueries - role,
                roleSearchResults = current.roleSearchResults - role,
                roleSearchLoading = current.roleSearchLoading - role,
                roleOfflineResults = current.roleOfflineResults - role,
            )
        }

        // Clean up the query flow
        roleQueryFlows.remove(role)
        roleSearchJobs[role]?.cancel()
        roleSearchJobs.remove(role)

        onChangesMade()
    }

    private fun performRoleSearch(
        role: ContributorRole,
        query: String,
    ) {
        roleSearchJobs[role]?.cancel()
        roleSearchJobs[role] =
            scope.launch {
                state.update {
                    it.copy(roleSearchLoading = it.roleSearchLoading + (role to true))
                }

                val response = contributorRepository.searchContributors(query, limit = SEARCH_LIMIT)

                // Filter out contributors already added for this role
                val currentContributorIds =
                    state.value
                        .contributorsForRole(role)
                        .mapNotNull { it.id }
                        .toSet()
                val filteredResults = response.contributors.filter { it.id !in currentContributorIds }

                state.update {
                    it.copy(
                        roleSearchResults = it.roleSearchResults + (role to filteredResults),
                        roleSearchLoading = it.roleSearchLoading + (role to false),
                        roleOfflineResults = it.roleOfflineResults + (role to response.isOfflineResult),
                    )
                }

                logger.debug { "Contributor search for $role: ${filteredResults.size} results for '$query'" }
            }
    }
}
