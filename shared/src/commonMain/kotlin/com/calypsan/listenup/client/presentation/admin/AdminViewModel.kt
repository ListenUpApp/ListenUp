package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadPendingUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.RevokeInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.SetOpenRegistrationUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the combined admin screen.
 *
 * Manages users, pending invites, and pending users on a single screen.
 * Subscribes to SSE events for real-time updates of pending users.
 */
class AdminViewModel(
    private val instanceRepository: InstanceRepository,
    private val loadUsersUseCase: LoadUsersUseCase,
    private val loadPendingUsersUseCase: LoadPendingUsersUseCase,
    private val loadInvitesUseCase: LoadInvitesUseCase,
    private val deleteUserUseCase: DeleteUserUseCase,
    private val revokeInviteUseCase: RevokeInviteUseCase,
    private val approveUserUseCase: ApproveUserUseCase,
    private val denyUserUseCase: DenyUserUseCase,
    private val setOpenRegistrationUseCase: SetOpenRegistrationUseCase,
    private val eventStreamRepository: EventStreamRepository,
) : ViewModel() {
    val state: StateFlow<AdminUiState>
        field = MutableStateFlow<AdminUiState>(AdminUiState.Loading)

    init {
        loadData()
        observeSSEEvents()
    }

    /**
     * Observe admin events for real-time pending user updates.
     */
    private fun observeSSEEvents() {
        viewModelScope.launch {
            eventStreamRepository.adminEvents.collect { event ->
                when (event) {
                    is AdminEvent.UserPending -> {
                        handleUserPending(event.user)
                    }

                    is AdminEvent.UserApproved -> {
                        handleUserApproved(event.user)
                    }

                    else -> { /* Other admin events handled elsewhere */ }
                }
            }
        }
    }

    private fun handleUserPending(user: AdminUserInfo) {
        logger.debug { "SSE: User pending - ${user.email}" }
        updateReady { ready ->
            // Only add if not already in list
            if (ready.pendingUsers.none { it.id == user.id }) {
                ready.copy(pendingUsers = ready.pendingUsers + user)
            } else {
                ready
            }
        }
    }

    private fun handleUserApproved(user: AdminUserInfo) {
        logger.debug { "SSE: User approved - ${user.email}" }
        updateReady { ready ->
            // Remove from pending
            val updatedPending = ready.pendingUsers.filter { it.id != user.id }
            // Only add to users if not already present (avoid duplicates from button + SSE)
            val updatedUsers =
                if (ready.users.none { it.id == user.id }) {
                    ready.users + user
                } else {
                    ready.users
                }
            ready.copy(pendingUsers = updatedPending, users = updatedUsers)
        }
    }

    fun loadData() {
        viewModelScope.launch {
            // Load all data in parallel — no dependencies between these calls
            val deferredInstance = async { instanceRepository.getInstance() }
            val deferredUsers = async { loadUsersUseCase() }
            val deferredPending = async { loadPendingUsersUseCase() }
            val deferredInvites = async { loadInvitesUseCase() }

            val openRegistration =
                when (val result = deferredInstance.await()) {
                    is Success -> result.data.openRegistration
                    is Failure -> false
                }

            val usersResult = deferredUsers.await()
            val pendingResult = deferredPending.await()
            val invitesResult = deferredInvites.await()

            // Users fetch is the primary load. If it fails on initial load, surface as Error.
            // If already Ready (refresh), surface as transient error on Ready.
            if (usersResult is Failure) {
                val message = "Failed to load users: ${usersResult.message}"
                state.update { current ->
                    if (current is AdminUiState.Ready) {
                        current.copy(error = message)
                    } else {
                        AdminUiState.Error(message)
                    }
                }
                return@launch
            }

            val users = (usersResult as Success).data
            val pendingUsers =
                when (pendingResult) {
                    is Success -> pendingResult.data
                    is Failure -> emptyList()
                }
            val pendingInvites =
                when (invitesResult) {
                    is Success -> invitesResult.data.filter { it.claimedAt == null }
                    is Failure -> emptyList()
                }
            val invitesError =
                when (invitesResult) {
                    is Success -> null
                    is Failure -> "Failed to load invites: ${invitesResult.message}"
                }

            // Sort users: root user first, then by creation date (oldest first)
            val sortedUsers =
                users.sortedWith(
                    compareByDescending<AdminUserInfo> { it.isRoot }
                        .thenBy { it.createdAt },
                )

            state.update { current ->
                if (current is AdminUiState.Ready) {
                    current.copy(
                        openRegistration = openRegistration,
                        users = sortedUsers,
                        pendingUsers = pendingUsers,
                        pendingInvites = pendingInvites,
                        error = invitesError,
                    )
                } else {
                    // First emission (from Loading) or recovering from Error:
                    // transition to Ready with fresh data and default UI fields.
                    AdminUiState.Ready(
                        openRegistration = openRegistration,
                        users = sortedUsers,
                        pendingUsers = pendingUsers,
                        pendingInvites = pendingInvites,
                        error = invitesError,
                    )
                }
            }
        }
    }

    fun deleteUser(userId: String) {
        viewModelScope.launch {
            updateReady { it.copy(deletingUserId = userId) }

            when (val result = deleteUserUseCase(userId)) {
                is Success -> {
                    updateReady { ready ->
                        ready.copy(
                            deletingUserId = null,
                            users = ready.users.filter { it.id != userId },
                        )
                    }
                }

                is Failure -> {
                    updateReady {
                        it.copy(
                            deletingUserId = null,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    fun revokeInvite(inviteId: String) {
        viewModelScope.launch {
            updateReady { it.copy(revokingInviteId = inviteId) }

            when (val result = revokeInviteUseCase(inviteId)) {
                is Success -> {
                    updateReady { ready ->
                        ready.copy(
                            revokingInviteId = null,
                            pendingInvites = ready.pendingInvites.filter { it.id != inviteId },
                        )
                    }
                }

                is Failure -> {
                    updateReady {
                        it.copy(
                            revokingInviteId = null,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    fun approveUser(userId: String) {
        viewModelScope.launch {
            updateReady { it.copy(approvingUserId = userId) }

            when (val result = approveUserUseCase(userId)) {
                is Success -> {
                    val approvedUser = result.data
                    updateReady { ready ->
                        // Move from pending to active users
                        val updatedPending = ready.pendingUsers.filter { it.id != userId }
                        // Only add to users if not already present (avoid duplicates from button + SSE)
                        val updatedUsers =
                            if (ready.users.none { it.id == userId }) {
                                ready.users + approvedUser
                            } else {
                                ready.users
                            }
                        ready.copy(
                            approvingUserId = null,
                            pendingUsers = updatedPending,
                            users = updatedUsers,
                        )
                    }
                }

                is Failure -> {
                    updateReady {
                        it.copy(
                            approvingUserId = null,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    fun denyUser(userId: String) {
        viewModelScope.launch {
            updateReady { it.copy(denyingUserId = userId) }

            when (val result = denyUserUseCase(userId)) {
                is Success -> {
                    updateReady { ready ->
                        ready.copy(
                            denyingUserId = null,
                            pendingUsers = ready.pendingUsers.filter { it.id != userId },
                        )
                    }
                }

                is Failure -> {
                    updateReady {
                        it.copy(
                            denyingUserId = null,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    fun setOpenRegistration(enabled: Boolean) {
        viewModelScope.launch {
            updateReady { it.copy(isTogglingOpenRegistration = true) }

            when (val result = setOpenRegistrationUseCase(enabled)) {
                is Success -> {
                    updateReady {
                        it.copy(
                            isTogglingOpenRegistration = false,
                            openRegistration = enabled,
                        )
                    }
                }

                is Failure -> {
                    updateReady {
                        it.copy(
                            isTogglingOpenRegistration = false,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Apply [transform] to state only if it is currently [AdminUiState.Ready].
     * No-ops when state is [AdminUiState.Loading] or [AdminUiState.Error].
     */
    private fun updateReady(transform: (AdminUiState.Ready) -> AdminUiState.Ready) {
        state.update { current ->
            if (current is AdminUiState.Ready) transform(current) else current
        }
    }
}

/**
 * UI state for the combined admin screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `loadData()` emission.
 * - [Ready] once data has loaded; carries openRegistration, users,
 *   pendingUsers, pendingInvites, per-action overlays
 *   (`deletingUserId`, `revokingInviteId`, `approvingUserId`,
 *   `denyingUserId`, `isTogglingOpenRegistration`), and a transient
 *   `error` surfaced as a snackbar.
 * - [Error] terminal state when the initial users load (or a retry from
 *   [Error]) fails. Refresh failures after we've reached [Ready] surface
 *   via the transient `error` field on [Ready] instead.
 */
sealed interface AdminUiState {
    data object Loading : AdminUiState

    /**
     * Admin data has loaded; carries users, pending users, pending invites,
     * per-action overlays, and a transient `error` surfaced as a snackbar.
     */
    data class Ready(
        val openRegistration: Boolean = false,
        val users: List<AdminUserInfo> = emptyList(),
        val pendingUsers: List<AdminUserInfo> = emptyList(),
        val pendingInvites: List<InviteInfo> = emptyList(),
        val deletingUserId: String? = null,
        val revokingInviteId: String? = null,
        val approvingUserId: String? = null,
        val denyingUserId: String? = null,
        val isTogglingOpenRegistration: Boolean = false,
        val error: String? = null,
    ) : AdminUiState

    /** Terminal state when the initial users load fails. */
    data class Error(
        val message: String,
    ) : AdminUiState
}
