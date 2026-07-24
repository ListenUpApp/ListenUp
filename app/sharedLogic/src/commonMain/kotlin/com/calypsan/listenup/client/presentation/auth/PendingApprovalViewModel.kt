package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.data.sync.reconnectDelayMillis
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/** Cadence for the automatic status re-check while awaiting approval. */
private const val POLL_INTERVAL_MS = 5_000L

/**
 * Upper bound on consecutive stream-reconnect attempts before this loop stops and leaves the
 * (never-stopping) poll as the sole driver. Bounded so a persistently failing stream — a
 * mid-registration server bounce, a hostile network path — can't reconnect forever; unbounded retry
 * on a serve-and-close-shaped endpoint is exactly the reconnect flood this migration exists to kill.
 */
private const val MAX_STREAM_RETRY_ATTEMPTS = 5

/**
 * ViewModel for the pending-approval screen.
 *
 * Subscribes to the RPC registration-status watch keyed by user id; the screen flips to
 * `Approved` (prompting re-login) or `Denied` based on server-side state changes. The watch
 * COMPLETES the moment the status turns terminal — that completion IS the signal, so a normal
 * return from `collect` ends the loop rather than triggering a reconnect. A stream failure (a
 * transport drop or a typed business error) retries with bounded backoff
 * ([MAX_STREAM_RETRY_ATTEMPTS]); the 5s poll below never stops on its own, so it remains the
 * "never stranded" fallback once that budget is exhausted or for any client where the stream never
 * delivers at all.
 */
class PendingApprovalViewModel(
    private val authSession: AuthSession,
    private val registrationStatusStream: RegistrationStatusStream,
    val userId: String,
    val email: String,
) : ViewModel() {
    val state: StateFlow<PendingApprovalUiState>
        field = MutableStateFlow<PendingApprovalUiState>(PendingApprovalUiState.Waiting)

    private var streamJob: Job? = null
    private var pollJob: Job? = null
    private var closed = false

    init {
        connectToStream()
        startStatusPolling()
    }

    override fun onCleared() {
        super.onCleared()
        close()
    }

    /**
     * Cancels the stream watch and the poll loop. Idempotent — safe to call more than once, or
     * after [onCleared] already ran.
     *
     * Android/Desktop never call this directly: [onCleared] delegates here when the framework's
     * `ViewModelStore` clears this entry. iOS has no `ViewModelStore` — a Koin `factory`-resolved
     * `PendingApprovalViewModel` is never told its owning screen went away — so
     * `PendingApprovalViewModelWrapper.deinit` calls [close] itself; without it, the stream/poll
     * jobs would run forever, orphaned, exactly the "iOS watcher never torn down" half of the
     * registration-status reconnect flood this migration exists to kill.
     */
    fun close() {
        if (closed) return
        closed = true
        streamJob?.cancel()
        pollJob?.cancel()
    }

    /**
     * Automatic pull fallback: re-checks the persisted status on a fixed cadence while waiting, so
     * an approved registrant advances even when the stream never delivers. An immediate check on
     * entry catches an already-decided registration; the loop then stops the moment a terminal
     * decision lands. Mirrors the server-side never-stranded poll.
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

    /**
     * Subscribes to the RPC watch. Normal completion of `collect` (a terminal status landed, or —
     * for an edge case like an unrecognised registration id — the watch ended with nothing to
     * report) ends the loop; there is nothing to reconnect. A thrown failure retries with bounded
     * backoff via [reconnectDelayMillis] up to [MAX_STREAM_RETRY_ATTEMPTS], after which this loop
     * gives up and the poll fallback carries the screen the rest of the way.
     */
    private fun connectToStream() {
        streamJob?.cancel()
        var retryAttempt = 0
        streamJob =
            viewModelScope.launch {
                while (isActive && state.value is PendingApprovalUiState.Waiting) {
                    try {
                        registrationStatusStream.streamStatus(userId).collect { status ->
                            retryAttempt = 0
                            handleStatusUpdate(status)
                        }
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (retryAttempt >= MAX_STREAM_RETRY_ATTEMPTS) {
                            logger.warn(e) {
                                "registration-status stream exhausted its retry budget; " +
                                    "relying on the poll fallback"
                            }
                            return@launch
                        }
                        logger.warn(e) { "registration-status stream failed; retrying with backoff" }
                        delay(reconnectDelayMillis(retryAttempt))
                        retryAttempt++
                    }
                }
            }
    }

    private suspend fun handleStatusUpdate(status: StreamedRegistrationStatus) {
        when (status) {
            is StreamedRegistrationStatus.Approved -> {
                logger.info { "Registration approved" }
                state.value = PendingApprovalUiState.Approved
            }

            is StreamedRegistrationStatus.Denied -> {
                logger.info { "Registration denied" }
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
     * Manually re-check approval status. Uses the reliable one-shot pull first (works even where
     * the stream doesn't), then re-opens the stream for instant future pushes if still waiting.
     * The "never stranded" manual fallback; safe to tap repeatedly.
     */
    fun checkStatus() {
        viewModelScope.launch {
            checkOnce()
            if (state.value is PendingApprovalUiState.Waiting) {
                connectToStream()
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
