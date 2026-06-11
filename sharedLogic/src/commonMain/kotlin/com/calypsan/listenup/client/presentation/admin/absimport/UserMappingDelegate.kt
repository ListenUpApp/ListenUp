package com.calypsan.listenup.client.presentation.admin.absimport

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.presentation.admin.ABSImportUiState
import com.calypsan.listenup.client.presentation.admin.SelectedUserDisplay
import com.calypsan.listenup.client.presentation.admin.UserMappingTab
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val MIN_SEARCH_QUERY_LEN = 2
private const val SEARCH_LIMIT = 10

internal class UserMappingDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ABSImportUiState>,
    private val errorBus: ErrorBus,
    private val absImportApi: ABSImportApiContract,
) {
    fun setUserMapping(
        absUserId: String,
        listenupUserId: String?,
    ) {
        state.updateReady { current ->
            val newMappings = current.userMappings.toMutableMap()
            if (listenupUserId != null) {
                newMappings[absUserId] = listenupUserId
            } else {
                newMappings.remove(absUserId)
            }
            current.copy(userMappings = newMappings)
        }
    }

    /**
     * Set the active tab in the user mapping step.
     */
    fun setUserMappingTab(tab: UserMappingTab) {
        state.updateReady { it.copy(userMappingTab = tab) }
    }

    /**
     * Called when a user search field gains focus.
     * Activates search for that specific user and clears previous search state.
     */
    fun activateUserSearch(absUserId: String) {
        state.updateReady {
            it.copy(
                activeSearchAbsUserId = absUserId,
                userSearchQuery = "",
                userSearchResults = emptyList(),
                isSearchingUsers = false,
            )
        }
    }

    /**
     * Called when a user search field loses focus.
     * Clears the active search state.
     */
    fun deactivateUserSearch() {
        state.updateReady {
            it.copy(
                activeSearchAbsUserId = null,
                userSearchQuery = "",
                userSearchResults = emptyList(),
                isSearchingUsers = false,
            )
        }
    }

    /**
     * Update search query for the active user search field.
     */
    fun updateUserSearchQuery(query: String) {
        state.updateReady { it.copy(userSearchQuery = query) }

        if (query.length < MIN_SEARCH_QUERY_LEN) {
            state.updateReady { it.copy(userSearchResults = emptyList(), isSearchingUsers = false) }
            return
        }

        scope.launch {
            state.updateReady { it.copy(isSearchingUsers = true) }
            try {
                when (val result = absImportApi.searchUsers(query, limit = SEARCH_LIMIT)) {
                    is AppResult.Success -> {
                        state.updateReady {
                            it.copy(
                                userSearchResults = result.data,
                                isSearchingUsers = false,
                            )
                        }
                    }

                    is AppResult.Failure -> {
                        logger.error { "User search failed: ${null as Exception?}" }
                        state.updateReady {
                            it.copy(
                                userSearchResults = emptyList(),
                                isSearchingUsers = false,
                            )
                        }
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "User search failed: ${e.message}" }
                state.updateReady {
                    it.copy(
                        userSearchResults = emptyList(),
                        isSearchingUsers = false,
                    )
                }
            }
        }
    }

    /**
     * Select a user from search results or suggestions and apply the mapping.
     */
    fun selectUser(
        absUserId: String,
        userId: String,
        email: String,
        displayName: String?,
    ) {
        scope.launch {
            // Show loading spinner on the tapped result while state propagates
            state.updateReady { it.copy(loadingUserItemId = userId) }

            // Store display info for the selected user
            val displayInfo =
                SelectedUserDisplay(
                    userId = userId,
                    email = email,
                    displayName = displayName,
                )

            state.updateReady { s ->
                val newDisplays = s.selectedUserDisplays.toMutableMap()
                newDisplays[absUserId] = displayInfo

                val newMappings = s.userMappings.toMutableMap()
                newMappings[absUserId] = userId

                s.copy(
                    selectedUserDisplays = newDisplays,
                    userMappings = newMappings,
                    // Clear search state
                    activeSearchAbsUserId = null,
                    userSearchQuery = "",
                    userSearchResults = emptyList(),
                    loadingUserItemId = null,
                )
            }
        }
    }

    /**
     * Clear the user mapping for an ABS user (allows re-searching).
     */
    fun clearUserMapping(absUserId: String) {
        state.updateReady { s ->
            val newDisplays = s.selectedUserDisplays.toMutableMap()
            newDisplays.remove(absUserId)

            val newMappings = s.userMappings.toMutableMap()
            newMappings.remove(absUserId)

            s.copy(
                selectedUserDisplays = newDisplays,
                userMappings = newMappings,
            )
        }
    }
}
