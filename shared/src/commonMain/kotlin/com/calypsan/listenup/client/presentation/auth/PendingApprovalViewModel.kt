package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val POLL_INTERVAL_MS = 5000L

/**
 * ViewModel for the pending-approval screen.
 *
 * Watches the server-side approval-status stream (SSE with polling fallback)
 * and surfaces the result. There is no client-side auto-login; once the
 * registration is approved, the screen prompts the user to log in normally.
 */
class PendingApprovalViewModel(
    private val authRepository: AuthRepository,
    private val authSession: AuthSession,
    private val registrationStatusStream: RegistrationStatusStream,
    val userId: String,
    val email: String,
) : ViewModel() {
    private val _state = MutableStateFlow<PendingApprovalUiState>(PendingApprovalUiState.Waiting)
    val state: StateFlow<PendingApprovalUiState> = _state.asStateFlow()

    private var sseJob: Job? = null
    private var pollingJob: Job? = null

    init {
        connectToSSE()
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        pollingJob?.cancel()
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
                    ErrorBus.emit(e)
                    logger.warn(e) { "SSE connection failed, falling back to polling" }
                    startPolling()
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

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    try {
                        val status = authRepository.checkRegistrationStatus(userId)
                        logger.debug { "Poll result: status=${status.status}, approved=${status.approved}" }

                        when {
                            status.approved -> {
                                logger.info { "Registration approved via polling" }
                                _state.value = PendingApprovalUiState.Approved
                                break
                            }

                            status.status == "denied" -> {
                                logger.info { "Registration denied via polling" }
                                handleDenied()
                                break
                            }
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ErrorBus.emit(e)
                        logger.warn(e) { "Failed to check registration status" }
                    }
                }
            }
    }

    private suspend fun handleDenied(message: String? = null) {
        authSession.clearPendingRegistration()
        _state.value =
            PendingApprovalUiState.Denied(
                message ?: "Your registration was denied by an administrator.",
            )
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
