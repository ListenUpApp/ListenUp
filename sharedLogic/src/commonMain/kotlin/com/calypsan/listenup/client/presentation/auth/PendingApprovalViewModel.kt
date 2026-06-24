package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** Cadence for the automatic status re-check while awaiting approval. */
private const val POLL_INTERVAL_MS = 5_000L

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
    val state: StateFlow<PendingApprovalUiState>
        field = MutableStateFlow<PendingApprovalUiState>(PendingApprovalUiState.Waiting)

    private var sseJob: Job? = null
    private var pollJob: Job? = null

    init {
        connectToSSE()
        startStatusPolling()
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        pollJob?.cancel()
    }

    /**
     * Automatic pull fallback: re-checks the persisted status on a fixed cadence while waiting, so
     * an approved registrant advances even when the SSE stream never delivers (e.g. iOS Darwin).
     * An immediate check on entry catches an already-decided registration; the loop then stops the
     * moment a terminal decision lands. Mirrors the server-side never-stranded poll.
     */
    private fun startStatusPolling() {
        pollJob?.cancel()
        pollJob =
            viewModelScope.launch {
                checkOnce()
                while (state.value is PendingApprovalUiState.Waiting) {
                    delay(POLL_INTERVAL_MS)
                    checkOnce()
                }
            }
    }

    /** One reliable pull of the persisted status; advances the screen on a terminal decision. */
    private suspend fun checkOnce() {
        val status = registrationStatusStream.fetchStatus(userId)
        if (status !is StreamedRegistrationStatus.Pending) {
            handleStatusUpdate(status)
        }
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
                state.value = PendingApprovalUiState.Approved
            }

            is StreamedRegistrationStatus.Denied -> {
                logger.info { "Registration denied via SSE" }
                handleDenied(status.message)
            }

            is StreamedRegistrationStatus.Pending -> {
                state.value = PendingApprovalUiState.Waiting
            }
        }
    }

    private fun handleDenied(message: String? = null) {
        // Surface the denial on-screen first. Clearing the pending registration here would flip
        // AuthState and unmount this screen before the message is ever seen — so the consumer shows
        // the denial, then calls [cancelRegistration] to clear it and return to login.
        state.value =
            PendingApprovalUiState.Denied(
                message ?: "Your registration was denied by an administrator.",
            )
    }

    /**
     * Manually re-check approval status. Uses the reliable one-shot pull first (works where the SSE
     * stream doesn't, e.g. iOS), then re-opens the stream for instant future pushes if still
     * waiting. The "never stranded" manual fallback; safe to tap repeatedly.
     */
    fun checkStatus() {
        viewModelScope.launch {
            checkOnce()
            if (state.value is PendingApprovalUiState.Waiting) {
                connectToSSE()
            }
        }
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
