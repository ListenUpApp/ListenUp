package com.calypsan.listenup.client.presentation.invite

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.repository.hostOfUrl
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the public invite claim flow: lookup a code, preview the
 * invite, then claim it (register + login in one step).
 *
 * Thin command-driven coordinator over [InviteRepository] — a successful
 * claim lands the user logged-in (the repository persists the issued session),
 * so the screen only needs to react to [ClaimInviteUiState.Claimed]. Failures
 * surface the typed [com.calypsan.listenup.api.error.AppError]'s body-level
 * `message` directly; no string-matching.
 */
class ClaimInviteViewModel(
    private val repository: InviteRepository,
    private val serverConfig: ServerConfig,
    private val instanceRepository: InstanceRepository,
    private val authSession: AuthSession,
) : ViewModel() {
    private var closed = false

    override fun onCleared() {
        super.onCleared()
        close()
    }

    /**
     * Cancels this ViewModel's coroutines. Idempotent. Android reaches it via [onCleared] when the
     * `ViewModelStore` clears the entry; iOS has no store, so the screen's wrapper calls it from its
     * `isolated deinit` (#1192) — else viewModelScope streams/one-shots orphan when the screen goes.
     */
    fun close() {
        if (closed) return
        closed = true
        viewModelScope.cancel()
    }

    val state: StateFlow<ClaimInviteUiState>
        field = MutableStateFlow<ClaimInviteUiState>(ClaimInviteUiState.Idle)

    private var code: String? = null

    /** The link's reachable server address, parked while the user decides whether to move to it. */
    private var pendingServerUrl: String? = null

    /**
     * Entry point for the deep-link claim path: pick a reachable server URL from the link, have the
     * user confirm it if it is not the server this device already uses, persist it, then look up
     * the invite — in that order.
     *
     * The link carries the admin's local [serverUrl] and, when the server advertises one, a
     * [remoteUrl] (WAN). We probe the local URL first and fall back to the remote, so an invitee off
     * the LAN still connects — the same Never-Stranded reachability probe
     * ([InstanceRepository.findReachableUrl]) that `ServerSelectViewModel` uses. If neither probes as
     * reachable we still persist the local URL so the lookup surfaces a real connection error.
     *
     * A link-supplied address is NEVER persisted without the user seeing it first. The link is
     * untrusted input, and the persisted server URL is the host every subsequent authenticated
     * request is sent to — so the flow parks on [ClaimInviteUiState.ConfirmServer] and waits for
     * [onConfirmServer]. The confirmation lands BEFORE the persist, not instead of it: the lookup
     * still runs after `setServerUrl` on one coroutine, because the RPC factory resolves its base
     * URL from [ServerConfig]. A device already pointed at the link's host is not moving anywhere,
     * so it skips the confirmation and goes straight through.
     *
     * The single launch per path is load-bearing. On a fresh install the lookup must not run until
     * [ServerConfig.setServerUrl] has completed; sequencing the set and the lookup on one coroutine
     * ([applyServerAndLookUp]) keeps that order deterministic. A null [serverUrl] is the manual-entry
     * path, where the user already selected a server via Connect, so the set step is skipped and we
     * go straight to lookup.
     */
    fun start(
        serverUrl: String?,
        code: String,
        remoteUrl: String? = null,
    ) {
        this.code = code
        viewModelScope.launch {
            val candidates = listOfNotNull(serverUrl, remoteUrl).distinct()
            if (candidates.isEmpty()) {
                lookUp(code)
                return@launch
            }
            val reachable = instanceRepository.findReachableUrl(candidates) ?: candidates.first()
            val currentHost = serverConfig.getServerUrl()?.value?.hostOfUrl()
            if (currentHost != null && currentHost == reachable.hostOfUrl()) {
                // Already pointed at this host — no move, nothing to confirm.
                applyServerAndLookUp(reachable)
                return@launch
            }
            pendingServerUrl = reachable
            state.value =
                ClaimInviteUiState.ConfirmServer(
                    host = reachable.hostOfUrl(),
                    signedInElsewhere = authSession.isAuthenticated(),
                )
        }
    }

    /** The user agreed to point this device at the link's server. Persists it, then looks the invite up. */
    fun onConfirmServer() {
        val pending = pendingServerUrl ?: return
        pendingServerUrl = null
        viewModelScope.launch { applyServerAndLookUp(pending) }
    }

    /** The user declined the link's server. Falls back to manual code entry against the current server. */
    fun onCancelServer() {
        pendingServerUrl = null
        state.value = ClaimInviteUiState.Idle
    }

    private suspend fun applyServerAndLookUp(reachable: String) {
        serverConfig.setServerUrl(ServerUrl(reachable))
        // Arm IP-follow for invite-claimed servers too: persist the server's stable instance
        // id (the same InstanceIdentity the mDNS relocation matches) so a later LAN address
        // change is followed. Best-effort — a probe failure leaves relocation disarmed, as
        // before, and never blocks the claim.
        when (val verify = instanceRepository.verifyServer(reachable)) {
            is AppResult.Success -> serverConfig.setConnectedServerId(verify.data.serverInfo.instanceId)
            is AppResult.Failure -> Unit
        }
        lookUp(code ?: return)
    }

    fun onCodeEntered(code: String) {
        this.code = code
        viewModelScope.launch {
            lookUp(code)
        }
    }

    private suspend fun lookUp(code: String) {
        state.value = ClaimInviteUiState.LookingUp
        state.value =
            when (val result = repository.lookupInvite(code)) {
                is AppResult.Success -> ClaimInviteUiState.Preview(result.data)
                is AppResult.Failure -> ClaimInviteUiState.Error(result.error.message)
            }
    }

    /**
     * Claim the invite. The UI collects the user's name as separate first/last fields
     * (matching registration); they are joined here into the single `displayName` the
     * contract carries — the one place the combination lives, so both platforms stay in
     * step. Blank names collapse to `null`, letting the server fall back to the invite's name.
     */
    fun onClaimSubmit(
        password: String,
        firstName: String,
        lastName: String,
    ) {
        val code = code ?: return
        val displayName = "${firstName.trim()} ${lastName.trim()}".trim().ifBlank { null }
        viewModelScope.launch {
            state.value = ClaimInviteUiState.Submitting
            state.value =
                when (val result = repository.claimInvite(code, password, displayName)) {
                    is AppResult.Success -> ClaimInviteUiState.Claimed
                    is AppResult.Failure -> ClaimInviteUiState.Error(result.error.message)
                }
        }
    }
}
