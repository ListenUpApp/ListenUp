package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the pending-approval screen.
 *
 * Subscribes to the SSE registration-status stream keyed by user id; the
 * screen flips to `Approved` (prompting re-login) or `Denied` based on
 * server-side state changes. If SSE drops, we stay in `Waiting` — the
 * user can always retry login from the manual flow.
 *
 * No client-side polling fallback: with the F4 product change, the user
 * retries `login()` to validate approval; instant SSE notification is a
 * nice-to-have, not load-bearing.
 */
class PendingApprovalViewModel(
    private val authSession: AuthSession,
    private val registrationStatusStream: RegistrationStatusStream,
    val userId: String,
    val email: String,
) : ViewModel() {
    private val _state = MutableStateFlow<PendingApprovalUiState>(PendingApprovalUiState.Waiting)
    val state: StateFlow<PendingApprovalUiState> = _state.asStateFlow()

    private var sseJob: Job? = null

    init {
        connectToSSE()
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
    }

    private fun connectToSSE() {
        sseJob?.cancel()
        sseJob =
            viewModelScope.launch {
                try {
                    registrationStatusStream.streamStatus(userId).collect { status ->
                        handleStatusUpdate(status)
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) {
                        "SSE registration-status stream failed; staying in Waiting — " +
                            "user can retry login from the manual flow"
                    }
                }
            }
    }

    private suspend fun handleStatusUpdate(status: StreamedRegistrationStatus) {
        when (status) {
            is StreamedRegistrationStatus.Approved -> {
                logger.info { "Registration approved via SSE" }
                _state.value = PendingApprovalUiState.Approved
            }

            is StreamedRegistrationStatus.Denied -> {
                logger.info { "Registration denied via SSE" }
                handleDenied(status.message)
            }

            is StreamedRegistrationStatus.Pending -> {
                _state.value = PendingApprovalUiState.Waiting
            }
        }
    }

    private fun handleDenied(message: String? = null) {
        // Surface the denial on-screen first. Clearing the pending registration here would flip
        // AuthState and unmount this screen before the message is ever seen — so the consumer shows
        // the denial, then calls [cancelRegistration] to clear it and return to login.
        _state.value =
            PendingApprovalUiState.Denied(
                message ?: "Your registration was denied by an administrator.",
            )
    }

    /**
     * Manually re-check approval status by re-opening the SSE stream — the server answers a fresh
     * subscription with the current status. The "never stranded" manual fallback to the always-on
     * stream; safe to tap repeatedly (each call cancels and replaces the previous subscription).
     */
    fun checkStatus() {
        connectToSSE()
    }

    /** Cancel the pending registration and return to login. */
    fun cancelRegistration() {
        viewModelScope.launch {
            authSession.clearPendingRegistration()
        }
    }

    /** Confirm the user has seen the approval and is moving on to log in. */
    fun acknowledgeApproval() {
        viewModelScope.launch {
            authSession.clearPendingRegistration()
        }
    }
}
