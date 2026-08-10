package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.PasswordResetStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.reconnectDelayMillis
import com.calypsan.listenup.client.domain.repository.PasswordResetRepository
import com.calypsan.listenup.core.error.ErrorBus
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
 * (never-stopping) poll as the sole driver. Mirrors [PendingApprovalViewModel]'s bound: a
 * persistently failing stream can't reconnect forever.
 */
private const val MAX_STREAM_RETRY_ATTEMPTS = 5

/**
 * ViewModel for the forgot-password flow: request a reset, wait for an admin to approve it, then
 * complete with the out-of-band code the admin conveys.
 *
 * Mirrors [PendingApprovalViewModel]'s stream-plus-poll shape exactly: [PasswordResetRepository
 * .observeStatus] is the push path and COMPLETES the moment the server-side status turns terminal
 * (`DENIED`/`CONSUMED`/`EXPIRED`) — that completion IS the signal, so a normal return from
 * `collect` ends the watch loop rather than triggering a reconnect. [PasswordResetRepository
 * .fetchStatus] backs the poll, which never stops on its own while awaiting approval and is the
 * "never stranded" fallback for a client whose stream never delivers at all.
 *
 * Construction resumes an in-flight request left over from a prior run — the "never stranded"
 * path for a user who closed the app while waiting and returns later, by phone, with the code.
 */
class ForgotPasswordViewModel(
    private val repository: PasswordResetRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<ForgotPasswordUiState>
        field = MutableStateFlow<ForgotPasswordUiState>(ForgotPasswordUiState.EnterEmail)

    private var streamJob: Job? = null
    private var pollJob: Job? = null
    private var closed = false

    init {
        viewModelScope.launch {
            val ticketId = repository.resumableTicketId()
            if (ticketId != null) {
                state.value = ForgotPasswordUiState.AwaitingApproval(ticketId)
                beginWatching(ticketId)
            }
        }
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
     * `ViewModelStore` clears this entry. iOS has no `ViewModelStore`, so the owning screen's
     * wrapper calls [close] itself from `isolated deinit`; without it the stream/poll jobs would
     * run forever, orphaned.
     */
    fun close() {
        if (closed) return
        closed = true
        stopWatching()
    }

    /** Opens a reset request for [email]. Always moves through [ForgotPasswordUiState.Submitting]. */
    fun requestReset(email: String) {
        viewModelScope.launch {
            state.value = ForgotPasswordUiState.Submitting
            when (val result = repository.requestReset(email)) {
                is AppResult.Success -> {
                    val ticketId = result.data.ticketId
                    state.value = ForgotPasswordUiState.AwaitingApproval(ticketId)
                    beginWatching(ticketId)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    state.value = ForgotPasswordUiState.Error(result.error.message)
                }
            }
        }
    }

    /**
     * Completes the reset with [code] plus [newPassword], using the ticket id of the current
     * [ForgotPasswordUiState.EnterCode] state. A no-op if called from any other state.
     *
     * A wrong code is not treated as a terminal failure — it surfaces the remaining attempt
     * budget on [ForgotPasswordUiState.EnterCode] so the user can retry, exactly mirroring
     * [PasswordResetRepository.completeReset]'s own "retain on failure" contract.
     */
    fun completeReset(
        code: String,
        newPassword: String,
    ) {
        val current = state.value as? ForgotPasswordUiState.EnterCode ?: return
        viewModelScope.launch {
            when (val result = repository.completeReset(current.ticketId, code, newPassword)) {
                is AppResult.Success -> {
                    stopWatching()
                    state.value = ForgotPasswordUiState.Complete
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    val attemptsRemaining = (result.error as? AuthError.ResetCodeIncorrect)?.attemptsRemaining
                    state.value =
                        current.copy(
                            attemptsRemaining = attemptsRemaining,
                            error = result.error.message,
                        )
                }
            }
        }
    }

    private fun beginWatching(ticketId: String) {
        connectToStream(ticketId)
        startStatusPolling(ticketId)
    }

    private fun stopWatching() {
        streamJob?.cancel()
        pollJob?.cancel()
    }

    /**
     * Automatic pull fallback: re-checks the persisted status on a fixed cadence while awaiting
     * approval, so an approved (or denied) request advances even when the stream never delivers.
     * The loop stops the moment the screen leaves [ForgotPasswordUiState.AwaitingApproval].
     */
    private fun startStatusPolling(ticketId: String) {
        pollJob?.cancel()
        pollJob =
            viewModelScope.launch {
                checkOnce(ticketId)
                while (state.value is ForgotPasswordUiState.AwaitingApproval) {
                    delay(POLL_INTERVAL_MS)
                    checkOnce(ticketId)
                }
            }
    }

    /** One reliable pull of the persisted status; advances the screen on a non-pending status. */
    private suspend fun checkOnce(ticketId: String) {
        val event = repository.fetchStatus(ticketId) ?: return
        if (event.status != PasswordResetStatus.PENDING) {
            handleStatusUpdate(event)
        }
    }

    /**
     * Subscribes to the RPC watch. Normal completion of `collect` — the server-side status
     * turned terminal — ends the loop; there is nothing to reconnect. A thrown failure (including
     * [com.calypsan.listenup.client.data.repository.PasswordResetStatusStreamFailure], a genuine
     * transport/stream fault) retries with bounded backoff up to [MAX_STREAM_RETRY_ATTEMPTS],
     * after which this loop gives up and the poll fallback carries the screen the rest of the way.
     */
    private fun connectToStream(ticketId: String) {
        streamJob?.cancel()
        var retryAttempt = 0
        streamJob =
            viewModelScope.launch {
                while (isActive && state.value is ForgotPasswordUiState.AwaitingApproval) {
                    try {
                        repository.observeStatus(ticketId).collect { event ->
                            retryAttempt = 0
                            handleStatusUpdate(event)
                        }
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (retryAttempt >= MAX_STREAM_RETRY_ATTEMPTS) {
                            logger.warn(e) {
                                "password-reset status stream exhausted its retry budget; " +
                                    "relying on the poll fallback"
                            }
                            return@launch
                        }
                        logger.warn(e) { "password-reset status stream failed; retrying with backoff" }
                        delay(reconnectDelayMillis(retryAttempt))
                        retryAttempt++
                    }
                }
            }
    }

    private fun handleStatusUpdate(event: PasswordResetStatusEvent) {
        state.value =
            when (event.status) {
                PasswordResetStatus.PENDING -> {
                    val ticketId = currentTicketId() ?: return
                    ForgotPasswordUiState.AwaitingApproval(ticketId)
                }

                PasswordResetStatus.APPROVED -> {
                    val ticketId = currentTicketId() ?: return
                    ForgotPasswordUiState.EnterCode(ticketId)
                }

                PasswordResetStatus.DENIED -> {
                    ForgotPasswordUiState.Denied
                }

                PasswordResetStatus.CONSUMED -> {
                    ForgotPasswordUiState.Complete
                }

                PasswordResetStatus.EXPIRED -> {
                    ForgotPasswordUiState.Error("Your reset request expired. Please start again.")
                }
            }
    }

    /** The ticket id carried by the current state, if any — [AwaitingApproval] or [EnterCode]. */
    private fun currentTicketId(): String? =
        when (val current = state.value) {
            is ForgotPasswordUiState.AwaitingApproval -> current.ticketId
            is ForgotPasswordUiState.EnterCode -> current.ticketId
            else -> null
        }
}
