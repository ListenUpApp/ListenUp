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
                            canEdit = user.permissions.canEdit,
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
     * Toggle the canEdit permission — whether this member may edit content metadata.
     *
     * Optimistically updates the UI state, then saves to server. Reverts on failure.
     */
    fun toggleCanEdit() {
        val ready = state.value as? UserDetailUiState.Ready ?: return
        togglePermission(
            name = "canEdit",
            previousValue = ready.canEdit,
            optimistic = { current, value -> current.copy(canEdit = value) },
            save = { value -> adminRepository.updateUser(userId = userId, canEdit = value) },
            reconcile = { current, user -> current.copy(canEdit = user.permissions.canEdit) },
        )
    }

    /**
     * Toggle the canShare permission.
     *
     * Optimistically updates the UI state, then saves to server. Reverts on failure.
     */
    fun toggleCanShare() {
        val ready = state.value as? UserDetailUiState.Ready ?: return
        togglePermission(
            name = "canShare",
            previousValue = ready.canShare,
            optimistic = { current, value -> current.copy(canShare = value) },
            save = { value -> adminRepository.updateUser(userId = userId, canShare = value) },
            reconcile = { current, user -> current.copy(canShare = user.permissions.canShare) },
        )
    }

    /**
     * The shared optimistic-toggle cycle behind both permission switches.
     *
     * Both flags round-trip identically — flip locally, save, then either reconcile against what
     * the server actually stored or revert — and #1270 added the second one. Two copies of this
     * would be two places for the revert to rot, and a permission toggle that fails to revert
     * leaves the admin looking at a grant the server never made.
     *
     * [reconcile] deliberately re-reads the flag off the server's response rather than trusting
     * the optimistic value: the server applies permissions wholesale, so its answer is the truth.
     */
    private fun togglePermission(
        name: String,
        previousValue: Boolean,
        optimistic: (UserDetailUiState.Ready, Boolean) -> UserDetailUiState.Ready,
        save: suspend (Boolean) -> AppResult<AdminUserInfo>,
        reconcile: (UserDetailUiState.Ready, AdminUserInfo) -> UserDetailUiState.Ready,
    ) {
        val ready = state.value as? UserDetailUiState.Ready ?: return
        if (ready.isProtected) return

        val newValue = !previousValue
        updateReady { optimistic(it, newValue).copy(isSaving = true) }

        viewModelScope.launch {
            when (val result = save(newValue)) {
                is AppResult.Success -> {
                    val updatedUser = result.data
                    logger.info { "Updated $name for user $userId to $newValue" }
                    updateReady { reconcile(it, updatedUser).copy(isSaving = false, user = updatedUser) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to update $name for user: $userId — ${result.error}" }
                    // Revert optimistic change and surface transient error in Ready.
                    updateReady { optimistic(it, previousValue).copy(isSaving = false, error = result.error) }
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
 *   (`canEdit`/`canShare`), `isProtected` guard, the `isSaving` overlay for optimistic
 *   permission toggling, and a transient `error` surfaced as a snackbar when
 *   a toggle fails after the initial load.
 * - [Error] terminal state when the initial load fails.
 */
sealed interface UserDetailUiState {
    data object Loading : UserDetailUiState

    /**
     * User has loaded; carries the canonical user, edit buffer (`canEdit`/`canShare`),
     * the `isProtected` guard, save overlay, and a transient `error`.
     */
    data class Ready(
        val user: AdminUserInfo,
        val canEdit: Boolean,
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
