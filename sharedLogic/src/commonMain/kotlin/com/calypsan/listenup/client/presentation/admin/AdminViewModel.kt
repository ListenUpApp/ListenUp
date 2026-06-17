package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.AdminEvent
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadPendingUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.GetRegistrationPolicyUseCase
import com.calypsan.listenup.client.domain.usecase.admin.RevokeInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.SetRegistrationPolicyUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Cadence for refreshing the pending-registrations list while the admin screen is open.
 *
 * The live admin-event firehose isn't wired yet ([com.calypsan.listenup.client.data.repository.EventStreamRepositoryImpl]
 * stubs `adminEvents` to `emptyFlow()`), so without this a newly-registered pending user only
 * appears after an app restart. This poll is the never-stranded refresh until that migration lands.
 */
private const val PENDING_POLL_INTERVAL_MS = 10_000L

/**
 * ViewModel for the combined admin screen.
 *
 * Manages users, pending invites, and pending users on a single screen.
 * Subscribes to SSE events for real-time updates of pending users.
 */
class AdminViewModel(
    private val getRegistrationPolicyUseCase: GetRegistrationPolicyUseCase,
    private val loadUsersUseCase: LoadUsersUseCase,
    private val loadPendingUsersUseCase: LoadPendingUsersUseCase,
    private val loadInvitesUseCase: LoadInvitesUseCase,
    private val deleteUserUseCase: DeleteUserUseCase,
    private val revokeInviteUseCase: RevokeInviteUseCase,
    private val approveUserUseCase: ApproveUserUseCase,
    private val denyUserUseCase: DenyUserUseCase,
    private val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase,
    private val eventStreamRepository: EventStreamRepository,
) : ViewModel() {
    val state: StateFlow<AdminUiState>
        field = MutableStateFlow<AdminUiState>(AdminUiState.Loading)

    init {
        loadData()
        observeSSEEvents()
        pollPendingUsers()
    }

    /**
     * Periodically re-fetch the pending-registrations list so a newly-registered applicant appears
     * without an app restart. Updates only the `pendingUsers` of an existing [AdminUiState.Ready]
     * (no-op while Loading/Error, and per-action overlays are preserved); a transient failure keeps
     * the current list.
     *
     * Gated on [state] having subscribers, so it only runs while the admin screen is actually on
     * screen — no wasted polling in the background. Stops automatically when the scope is cancelled.
     */
    private fun pollPendingUsers() {
        viewModelScope.launch {
            state.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { isObserved ->
                    if (!isObserved) return@collectLatest
                    while (true) {
                        delay(PENDING_POLL_INTERVAL_MS)
                        when (val result = loadPendingUsersUseCase()) {
                            is AppResult.Success -> updateReady { it.copy(pendingUsers = result.data) }
                            is AppResult.Failure -> Unit
                        }
                    }
                }
        }
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
            val deferredOpenReg = async { getRegistrationPolicyUseCase() }
            val deferredUsers = async { loadUsersUseCase() }
            val deferredPending = async { loadPendingUsersUseCase() }
            val deferredInvites = async { loadInvitesUseCase() }

            val registrationPolicy =
                when (val result = deferredOpenReg.await()) {
                    is AppResult.Success -> result.data
                    is AppResult.Failure -> RegistrationPolicy.CLOSED
                }

            val usersResult = deferredUsers.await()
            val pendingResult = deferredPending.await()
            val invitesResult = deferredInvites.await()

            // Users fetch is the primary load. If it fails on initial load, surface as Error.
            // If already Ready (refresh), surface as transient error on Ready.
            if (usersResult is AppResult.Failure) {
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

            val users = (usersResult as AppResult.Success).data
            val pendingUsers =
                when (pendingResult) {
                    is AppResult.Success -> pendingResult.data
                    is AppResult.Failure -> emptyList()
                }
            val pendingInvites =
                when (invitesResult) {
                    is AppResult.Success -> invitesResult.data.filter { it.claimedAt == null }
                    is AppResult.Failure -> emptyList()
                }
            val invitesError =
                when (invitesResult) {
                    is AppResult.Success -> null
                    is AppResult.Failure -> "Failed to load invites: ${invitesResult.message}"
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
                        registrationPolicy = registrationPolicy,
                        users = sortedUsers,
                        pendingUsers = pendingUsers,
                        pendingInvites = pendingInvites,
                        error = invitesError,
                    )
                } else {
                    // First emission (from Loading) or recovering from Error:
                    // transition to Ready with fresh data and default UI fields.
                    AdminUiState.Ready(
                        registrationPolicy = registrationPolicy,
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
                is AppResult.Success -> {
                    updateReady { ready ->
                        ready.copy(
                            deletingUserId = null,
                            users = ready.users.filter { it.id != userId },
                        )
                    }
                }

                is AppResult.Failure -> {
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
                is AppResult.Success -> {
                    updateReady { ready ->
                        ready.copy(
                            revokingInviteId = null,
                            pendingInvites = ready.pendingInvites.filter { it.id != inviteId },
                        )
                    }
                }

                is AppResult.Failure -> {
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
                is AppResult.Success -> {
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

                is AppResult.Failure -> {
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
                is AppResult.Success -> {
                    updateReady { ready ->
                        ready.copy(
                            denyingUserId = null,
                            pendingUsers = ready.pendingUsers.filter { it.id != userId },
                        )
                    }
                }

                is AppResult.Failure -> {
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

    fun setRegistrationPolicy(policy: RegistrationPolicy) {
        viewModelScope.launch {
            updateReady { it.copy(isTogglingRegistrationPolicy = true) }

            when (val result = setRegistrationPolicyUseCase(policy)) {
                is AppResult.Success -> {
                    updateReady {
                        it.copy(
                            isTogglingRegistrationPolicy = false,
                            registrationPolicy = policy,
                        )
                    }
                }

                is AppResult.Failure -> {
                    updateReady {
                        it.copy(
                            isTogglingRegistrationPolicy = false,
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
 * - [Ready] once data has loaded; carries registrationPolicy, users,
 *   pendingUsers, pendingInvites, per-action overlays
 *   (`deletingUserId`, `revokingInviteId`, `approvingUserId`,
 *   `denyingUserId`, `isTogglingRegistrationPolicy`), and a transient
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
        val registrationPolicy: RegistrationPolicy = RegistrationPolicy.CLOSED,
        val users: List<AdminUserInfo> = emptyList(),
        val pendingUsers: List<AdminUserInfo> = emptyList(),
        val pendingInvites: List<InviteInfo> = emptyList(),
        val deletingUserId: String? = null,
        val revokingInviteId: String? = null,
        val approvingUserId: String? = null,
        val denyingUserId: String? = null,
        val isTogglingRegistrationPolicy: Boolean = false,
        val error: String? = null,
    ) : AdminUiState

    /** Terminal state when the initial users load fails. */
    data class Error(
        val message: String,
    ) : AdminUiState
}
