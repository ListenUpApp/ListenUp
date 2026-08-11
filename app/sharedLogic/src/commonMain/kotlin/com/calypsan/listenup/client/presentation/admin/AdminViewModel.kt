package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.auth.PasswordResetDecisionOutcome
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DecidePasswordResetUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadPasswordResetRequestsUseCase
import com.calypsan.listenup.client.domain.usecase.admin.GetRegistrationPolicyUseCase
import com.calypsan.listenup.client.domain.usecase.admin.RevokeInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.SetRegistrationPolicyUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the combined admin screen.
 *
 * Manages users, pending invites, and pending users on a single screen. The active-user and
 * pending-registration lists are derived from [AdminRepository.observeRoster] — the Room-backed,
 * server-synced roster — so a new registration or a claimed invite appears the moment the sync
 * echo lands, with no poll and no dead event reducer standing in for it.
 */
class AdminViewModel(
    private val getRegistrationPolicyUseCase: GetRegistrationPolicyUseCase,
    private val loadInvitesUseCase: LoadInvitesUseCase,
    private val deleteUserUseCase: DeleteUserUseCase,
    private val revokeInviteUseCase: RevokeInviteUseCase,
    private val approveUserUseCase: ApproveUserUseCase,
    private val denyUserUseCase: DenyUserUseCase,
    private val setRegistrationPolicyUseCase: SetRegistrationPolicyUseCase,
    private val loadPasswordResetRequestsUseCase: LoadPasswordResetRequestsUseCase,
    private val decidePasswordResetUseCase: DecidePasswordResetUseCase,
    private val adminRepository: AdminRepository,
) : ViewModel() {
    val state: StateFlow<AdminUiState>
        field = MutableStateFlow<AdminUiState>(AdminUiState.Loading)

    init {
        loadData()
        observeRoster()
    }

    /**
     * Collects the live admin roster and splits it into `users` (ACTIVE) and `pendingUsers`
     * (PENDING_APPROVAL). Cooperates with [loadData]: it constructs [AdminUiState.Ready] on the
     * first emission if [loadData] hasn't landed yet, and only touches `users`/`pendingUsers` on
     * an existing [AdminUiState.Ready] otherwise — so neither collector clobbers the other's
     * fields (registrationPolicy, pendingInvites, per-action overlays).
     */
    private fun observeRoster() {
        viewModelScope.launch {
            adminRepository.observeRoster().collect { roster ->
                val active = sortUsers(roster.filter { it.status == "ACTIVE" })
                val pending = roster.filter { it.status == "PENDING_APPROVAL" }
                state.update { current ->
                    when (current) {
                        is AdminUiState.Ready -> current.copy(users = active, pendingUsers = pending)
                        AdminUiState.Loading -> AdminUiState.Ready(users = active, pendingUsers = pending)
                    }
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            // Load all data in parallel — no dependencies between these calls
            val deferredOpenReg = async { getRegistrationPolicyUseCase() }
            val deferredInvites = async { loadInvitesUseCase() }
            val deferredPasswordResets = async { loadPasswordResetRequestsUseCase() }

            val registrationPolicy =
                when (val result = deferredOpenReg.await()) {
                    is AppResult.Success -> result.data
                    is AppResult.Failure -> RegistrationPolicy.CLOSED
                }

            val invitesResult = deferredInvites.await()
            val passwordResetsResult = deferredPasswordResets.await()

            // Every section degrades independently. A single failed fetch must never black out the
            // whole page — that strands the admin without the (independent) registration-policy
            // control and every other section. Failures surface as a sectional error banner
            // on Ready, never as a full Error screen.
            val pendingInvites =
                when (invitesResult) {
                    is AppResult.Success -> invitesResult.data.filter { it.claimedAt == null }
                    is AppResult.Failure -> emptyList()
                }
            val pendingPasswordResets =
                when (passwordResetsResult) {
                    is AppResult.Success -> passwordResetsResult.data
                    is AppResult.Failure -> emptyList()
                }
            val loadError =
                when {
                    invitesResult is AppResult.Failure -> {
                        "Failed to load invites: ${invitesResult.message}"
                    }

                    passwordResetsResult is AppResult.Failure -> {
                        "Failed to load password resets: ${passwordResetsResult.message}"
                    }

                    else -> {
                        null
                    }
                }

            state.update { current ->
                if (current is AdminUiState.Ready) {
                    current.copy(
                        registrationPolicy = registrationPolicy,
                        pendingInvites = pendingInvites,
                        pendingPasswordResets = pendingPasswordResets,
                        error = loadError,
                    )
                } else {
                    // First emission (from Loading) or recovering from Error:
                    // transition to Ready with fresh data and default UI fields. Users/pendingUsers
                    // stay at their AdminUiState.Ready defaults (empty) until observeRoster emits.
                    AdminUiState.Ready(
                        registrationPolicy = registrationPolicy,
                        pendingInvites = pendingInvites,
                        pendingPasswordResets = pendingPasswordResets,
                        error = loadError,
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
                        // Only add to users if not already present (avoid duplicates from button + sync echo)
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

    /**
     * Approve or deny a pending password-reset request.
     *
     * On approval, the code returned by [decidePasswordResetUseCase] is surfaced via
     * [AdminUiState.Ready.resetCodeToConvey] — this is the **only** place in the client
     * that ever sees it. The screen is responsible for showing it once and requiring an
     * explicit [dismissResetCode] before it disappears; nothing here re-fetches it.
     */
    fun decidePasswordReset(
        requestId: String,
        approved: Boolean,
    ) {
        val recipientName =
            (state.value as? AdminUiState.Ready)
                ?.pendingPasswordResets
                ?.firstOrNull { it.id == requestId }
                ?.displayName

        viewModelScope.launch {
            updateReady { it.copy(decidingPasswordResetId = requestId) }

            when (val result = decidePasswordResetUseCase(requestId, approved)) {
                is AppResult.Success -> {
                    val approvedCode = (result.data as? PasswordResetDecisionOutcome.Approved)?.code
                    updateReady { ready ->
                        ready.copy(
                            decidingPasswordResetId = null,
                            pendingPasswordResets = ready.pendingPasswordResets.filter { it.id != requestId },
                            resetCodeToConvey = approvedCode ?: ready.resetCodeToConvey,
                            resetCodeRecipientName =
                                if (approvedCode !=
                                    null
                                ) {
                                    recipientName
                                } else {
                                    ready.resetCodeRecipientName
                                },
                        )
                    }
                }

                is AppResult.Failure -> {
                    updateReady {
                        it.copy(
                            decidingPasswordResetId = null,
                            error = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Clears the surfaced reset code once the admin has conveyed it. It is never
     * retrievable again after this — there is no other surface that returns it.
     */
    fun dismissResetCode() {
        updateReady { it.copy(resetCodeToConvey = null, resetCodeRecipientName = null) }
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

    /** Root user first, then by creation date (oldest first). */
    private fun sortUsers(users: List<AdminUserInfo>): List<AdminUserInfo> =
        users.sortedWith(
            compareByDescending<AdminUserInfo> { it.isRoot }
                .thenBy { it.createdAt },
        )

    /**
     * Apply [transform] to state only if it is currently [AdminUiState.Ready].
     * No-ops when state is [AdminUiState.Loading].
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
 *   pendingUsers, pendingInvites, pendingPasswordResets, per-action overlays
 *   (`deletingUserId`, `revokingInviteId`, `approvingUserId`,
 *   `denyingUserId`, `decidingPasswordResetId`, `isTogglingRegistrationPolicy`),
 *   the one-time `resetCodeToConvey`/`resetCodeRecipientName` pair, and a
 *   transient `error` surfaced as a snackbar. Initial-load failures also
 *   surface via the transient `error` field on [Ready].
 *
 * Deliberately just **two** subtypes — `SwiftExportSourcePatcher.expectedSealedSubtypeCounts`
 * pins that count for the Swift Export surface, so new admin data is added as fields on
 * [Ready] rather than a new sealed arm.
 */
sealed interface AdminUiState {
    data object Loading : AdminUiState

    /**
     * Admin data has loaded; carries users, pending users, pending invites, pending
     * password-reset requests, per-action overlays, the one-time reset code awaiting
     * conveyance, and a transient `error` surfaced as a snackbar.
     */
    data class Ready(
        val registrationPolicy: RegistrationPolicy = RegistrationPolicy.CLOSED,
        val users: List<AdminUserInfo> = emptyList(),
        val pendingUsers: List<AdminUserInfo> = emptyList(),
        val pendingInvites: List<InviteInfo> = emptyList(),
        val pendingPasswordResets: List<PasswordResetRequest> = emptyList(),
        val deletingUserId: String? = null,
        val revokingInviteId: String? = null,
        val approvingUserId: String? = null,
        val denyingUserId: String? = null,
        val decidingPasswordResetId: String? = null,
        val isTogglingRegistrationPolicy: Boolean = false,
        /**
         * The code an admin just minted by approving a reset, surfaced exactly once so it
         * can be conveyed out of band. `null` when no code is awaiting conveyance.
         */
        val resetCodeToConvey: String? = null,
        /** Display name of the requester [resetCodeToConvey] is for, for the dialog copy. */
        val resetCodeRecipientName: String? = null,
        val error: String? = null,
    ) : AdminUiState
}
