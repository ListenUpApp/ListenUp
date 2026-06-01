package com.calypsan.listenup.client.presentation.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.InviteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {
    private val _state = MutableStateFlow<ClaimInviteUiState>(ClaimInviteUiState.Idle)
    val state: StateFlow<ClaimInviteUiState> = _state.asStateFlow()

    private var code: String? = null

    fun onCodeEntered(code: String) {
        this.code = code
        viewModelScope.launch {
            _state.value = ClaimInviteUiState.LookingUp
            _state.value =
                when (val result = repository.lookupInvite(code)) {
                    is AppResult.Success -> ClaimInviteUiState.Preview(result.data)
                    is AppResult.Failure -> ClaimInviteUiState.Error(result.error.message)
                }
        }
    }

    fun onClaimSubmit(
        password: String,
        displayName: String? = null,
    ) {
        val code = code ?: return
        viewModelScope.launch {
            _state.value = ClaimInviteUiState.Submitting
            _state.value =
                when (val result = repository.claimInvite(code, password, displayName)) {
                    is AppResult.Success -> ClaimInviteUiState.Claimed
                    is AppResult.Failure -> ClaimInviteUiState.Error(result.error.message)
                }
        }
    }
}
