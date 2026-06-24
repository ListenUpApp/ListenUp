package com.calypsan.listenup.client.presentation.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
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
) : ViewModel() {
    val state: StateFlow<ClaimInviteUiState>
        field = MutableStateFlow<ClaimInviteUiState>(ClaimInviteUiState.Idle)

    private var code: String? = null

    /**
     * Entry point for the deep-link claim path: persist the server URL the link
     * carries, then look up the invite — in that order, on one coroutine.
     *
     * The single launch is load-bearing. The RPC factory resolves its base URL
     * from [ServerConfig], so on a fresh install the lookup must not run until
     * [ServerConfig.setServerUrl] has completed; sequencing both on one coroutine
     * makes the set-before-lookup order deterministic. A null [serverUrl] is the
     * manual-entry path, where the user already selected a server via Connect, so
     * the set step is skipped and we go straight to lookup.
     */
    fun start(
        serverUrl: String?,
        code: String,
    ) {
        this.code = code
        viewModelScope.launch {
            if (serverUrl != null) {
                serverConfig.setServerUrl(ServerUrl(serverUrl))
            }
            lookUp(code)
        }
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

    fun onClaimSubmit(
        password: String,
        displayName: String? = null,
    ) {
        val code = code ?: return
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
