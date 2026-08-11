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
class ForgotPasswordViewModel internal constructor(
    private val repository: PasswordResetRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<ForgotPasswordUiState>
        field = MutableStateFlow<ForgotPasswordUiState>(ForgotPasswordUiState.EnterEmail)

    private var streamJob: Job? = null
    private var pollJob: Job? = null
    private var closed = false

    /** The address most recently requested, so a declined request can be re-opened in one tap. */
    private var lastRequestedEmail: String? = null

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

    /**
     * Re-opens a declined request, putting it back in front of an admin.
     *
     * A decline is usually a misunderstanding rather than a verdict, so the requester should not
     * have to walk back through sign-in to ask a second time. This re-sends the address they
     * already gave; [PasswordResetRepository.requestReset] mints a fresh claim and the server
     * supersedes any earlier live ticket, so no stale request is left behind.
     *
     * Falls back to the email step if the address is gone — the ViewModel is scoped to the screen,
     * so a process death takes the address with it, and asking again beats failing silently.
     */
    fun retryRequest() {
        val email = lastRequestedEmail
        if (email == null) {
            state.value = ForgotPasswordUiState.EnterEmail
            return
        }
        requestReset(email)
    }

    /** Opens a reset request for [email]. Always moves through [ForgotPasswordUiState.Submitting]. */
    fun requestReset(email: String) {
        lastRequestedEmail = email
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
        val ticketId = (state.value as? ForgotPasswordUiState.EnterCode)?.ticketId ?: return
        viewModelScope.launch {
            when (val result = repository.completeReset(ticketId, code, newPassword)) {
                is AppResult.Success -> {
                    stopWatching()
                    state.value = ForgotPasswordUiState.Complete
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    // Re-read state.value here rather than writing back the snapshot captured
                    // above: the background watch runs concurrently with this network call and
                    // may have discovered a genuine terminal status (EXPIRED/DENIED) while it was
                    // in flight. Only decorate with the wrong-code feedback if the screen is
                    // STILL EnterCode for the SAME ticket — otherwise this would clobber the real
                    // terminal state with stale feedback for a request that's already dead.
                    val latest = state.value as? ForgotPasswordUiState.EnterCode
                    if (latest != null && latest.ticketId == ticketId) {
                        val attemptsRemaining = (result.error as? AuthError.ResetCodeIncorrect)?.attemptsRemaining
                        state.value =
                            latest.copy(
                                attemptsRemaining = attemptsRemaining,
                                error = result.error.message,
                            )
                    }
                }
            }
        }
    }

    /**
     * Manually re-checks status: the reliable one-shot pull first (works even where the stream
     * doesn't), then re-opens the stream for instant future pushes if still awaiting approval.
     * The "never stranded" manual fallback for a "Check Status" affordance; safe to tap
     * repeatedly. A no-op unless the screen is currently watching a ticket.
     */
    fun checkStatus() {
        val ticketId = state.value.ticketIdOrNull() ?: return
        viewModelScope.launch {
            checkOnce(ticketId)
            if (state.value is ForgotPasswordUiState.AwaitingApproval) {
                connectToStream(ticketId)
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

    /**
     * Folds [event] into [state]. **Not** distinct-until-changed on the wire — the server's watch
     * is a fixed-interval poll that re-emits the current status every tick for as long as it
     * stays `PENDING` or `APPROVED`, so this runs repeatedly for the *same* status while the user
     * sits on one screen. [nextState] is written to tolerate that: a repeated tick must compute
     * "no change" rather than a fresh instance of the same state, or it would silently erase
     * whatever the user has done on that screen since (see [nextState]'s own KDoc).
     *
     * Also abandons the persisted claim/ticket on a terminal status the requester didn't
     * themselves complete — `DENIED` or `EXPIRED` — mirroring [PasswordResetRepository
     * .completeReset]'s own successful-completion clear, so a later cold start never resumes a
     * dead ticket into [ForgotPasswordUiState.AwaitingApproval]. `CONSUMED` needs no equivalent
     * call here: it only ever follows this device's own successful [completeReset], which already
     * clears both keys as part of that same success path.
     */
    private suspend fun handleStatusUpdate(event: PasswordResetStatusEvent) {
        val next = nextState(state.value, event) ?: return
        state.value = next
        when (event.status) {
            PasswordResetStatus.DENIED, PasswordResetStatus.EXPIRED -> repository.abandonPendingRequest()
            PasswordResetStatus.PENDING, PasswordResetStatus.APPROVED, PasswordResetStatus.CONSUMED -> Unit
        }
    }

    /**
     * Computes what [state] should become for [event] given [current] — or `null` when there is
     * nothing to do.
     *
     * The `PENDING` and `APPROVED` branches guard against re-synthesizing a state the screen is
     * already downstream of. Concretely: a repeated `APPROVED` tick while the user is already on
     * [ForgotPasswordUiState.EnterCode] must **not** overwrite their retained
     * `attemptsRemaining`/`error` wrong-code feedback with a blank `EnterCode(ticketId)` — that is
     * the whole bug this function exists to prevent. `PENDING` carries the identical guard even
     * though [ForgotPasswordUiState.AwaitingApproval] has no other field today: it would inherit
     * this exact bug shape the moment that state grows one, so the guard is written proactively
     * rather than left as a known gap.
     */
    private fun nextState(
        current: ForgotPasswordUiState,
        event: PasswordResetStatusEvent,
    ): ForgotPasswordUiState? =
        when (event.status) {
            PasswordResetStatus.PENDING -> {
                if (current is ForgotPasswordUiState.AwaitingApproval) {
                    null
                } else {
                    current.ticketIdOrNull()?.let { ForgotPasswordUiState.AwaitingApproval(it) }
                }
            }

            PasswordResetStatus.APPROVED -> {
                if (current is ForgotPasswordUiState.EnterCode) {
                    null
                } else {
                    current.ticketIdOrNull()?.let { ForgotPasswordUiState.EnterCode(it) }
                }
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

    /** The ticket id carried by this state, if any — [AwaitingApproval] or [EnterCode]. */
    private fun ForgotPasswordUiState.ticketIdOrNull(): String? =
        when (this) {
            is ForgotPasswordUiState.AwaitingApproval -> ticketId
            is ForgotPasswordUiState.EnterCode -> ticketId
            else -> null
        }
}
