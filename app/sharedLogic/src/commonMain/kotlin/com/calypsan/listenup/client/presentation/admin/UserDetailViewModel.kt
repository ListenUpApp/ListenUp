package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the user detail screen.
 *
 * Manages viewing and editing a single user's details and permissions.
 * Allows toggling canShare permission for non-protected users.
 */
class UserDetailViewModel(
    private val userId: String,
    private val adminRepository: AdminRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<UserDetailUiState>
        field = MutableStateFlow<UserDetailUiState>(UserDetailUiState.Loading)

    init {
        loadUser()
    }

    /**
     * Load the user details from the server.
     *
     * Initial load transitions Loading -> Ready or Loading -> Error. A subsequent
     * re-load from Error transitions back to Ready on success, or stays in Error
     * with the new message on failure.
     */
    private fun loadUser() {
        viewModelScope.launch {
            when (val result = adminRepository.getUser(userId)) {
                is AppResult.Success -> {
                    val user = result.data
                    state.update {
                        UserDetailUiState.Ready(
                            user = user,
                            canShare = user.permissions.canShare,
                            isProtected = user.isProtected,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to load user: $userId — ${result.error}" }
                    state.update {
                        UserDetailUiState.Error(
                            error = result.error,
                        )
                    }
                }
            }
        }
    }

    /**
     * Toggle the canShare permission.
     *
     * Optimistically updates the UI state, then saves to server.
     * Reverts on failure.
     */
    fun toggleCanShare() {
        val ready = state.value as? UserDetailUiState.Ready ?: return
        if (ready.isProtected) return

        val previousValue = ready.canShare
        val newValue = !previousValue

        // Optimistic update
        updateReady { it.copy(canShare = newValue, isSaving = true) }

        viewModelScope.launch {
            when (val result = adminRepository.updateUser(userId = userId, canShare = newValue)) {
                is AppResult.Success -> {
                    val updatedUser = result.data
                    logger.info { "Updated canShare for user $userId to $newValue" }
                    updateReady {
                        it.copy(
                            isSaving = false,
                            user = updatedUser,
                            canShare = updatedUser.permissions.canShare,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to update canShare for user: $userId — ${result.error}" }
                    // Revert optimistic change and surface transient error in Ready.
                    updateReady {
                        it.copy(
                            isSaving = false,
                            canShare = previousValue,
                            error = result.error,
                        )
                    }
                }
            }
        }
    }

    /**
     * Clear the transient Ready error (snackbar acknowledgement).
     */
    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /**
     * Apply [transform] to state only if it is currently [UserDetailUiState.Ready].
     * No-ops when state is [UserDetailUiState.Loading] or [UserDetailUiState.Error].
     */
    private fun updateReady(transform: (UserDetailUiState.Ready) -> UserDetailUiState.Ready) {
        state.update { current ->
            if (current is UserDetailUiState.Ready) transform(current) else current
        }
    }
}

/**
 * UI state for the user detail screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `getUser` response.
 * - [Ready] once the user has loaded; carries the user, edit buffer
 *   (`canShare`), `isProtected` guard, the `isSaving` overlay for optimistic
 *   permission toggling, and a transient `error` surfaced as a snackbar when
 *   a toggle fails after the initial load.
 * - [Error] terminal state when the initial load fails.
 */
sealed interface UserDetailUiState {
    data object Loading : UserDetailUiState

    /**
     * User has loaded; carries the canonical user, edit buffer (`canShare`),
     * the `isProtected` guard, save overlay, and a transient `error`.
     */
    data class Ready(
        val user: AdminUserInfo,
        val canShare: Boolean,
        val isProtected: Boolean,
        val isSaving: Boolean = false,
        val error: AppError? = null,
    ) : UserDetailUiState

    /** Terminal state when the initial user load fails. */
    data class Error(
        val error: AppError,
    ) : UserDetailUiState
}
