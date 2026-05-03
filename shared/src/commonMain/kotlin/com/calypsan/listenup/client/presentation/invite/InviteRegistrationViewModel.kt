package com.calypsan.listenup.client.presentation.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the invite registration screen.
 *
 * Manages a sealed [InviteRegistrationUiState] that tracks the two-phase flow:
 * 1. Load invite details from the server
 * 2. Validate password input and submit registration
 *
 * On successful registration, stores tokens which triggers AuthState.Authenticated,
 * causing automatic navigation to the Library screen.
 */
class InviteRegistrationViewModel(
    private val inviteRepository: InviteRepository,
    private val serverConfig: ServerConfig,
    private val authSession: AuthSession,
    private val userRepository: UserRepository,
    private val serverUrl: String,
    private val inviteCode: String,
) : ViewModel() {
    private val _state = MutableStateFlow<InviteRegistrationUiState>(InviteRegistrationUiState.Loading)
    val state: StateFlow<InviteRegistrationUiState> = _state.asStateFlow()

    init {
        loadInviteDetails()
    }

    /** Load invite details from the server. */
    fun loadInviteDetails() {
        viewModelScope.launch {
            _state.value = InviteRegistrationUiState.Loading

            try {
                val details = inviteRepository.getInviteDetails(serverUrl, inviteCode)

                _state.value =
                    if (!details.valid) {
                        InviteRegistrationUiState.Invalid(
                            "This invite is no longer valid. It may have already been used or expired.",
                        )
                    } else {
                        InviteRegistrationUiState.Ready(details)
                    }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorBus.emit(e)
                _state.value = InviteRegistrationUiState.LoadError(e.message ?: "Failed to load invite")
            }
        }
    }

    /**
     * Submit registration with the given passwords.
     *
     * Validates passwords locally first, then submits to the server.
     * On success, saves auth tokens which triggers automatic navigation.
     */
    fun submitRegistration(
        password: String,
        confirmPassword: String,
    ) {
        val details = currentDetails() ?: return

        if (password.length < PASSWORD_MIN) {
            _state.value =
                InviteRegistrationUiState.SubmitError(
                    details = details,
                    errorType = InviteErrorType.ValidationError(InviteField.PASSWORD),
                )
            return
        }

        if (password != confirmPassword) {
            _state.value =
                InviteRegistrationUiState.SubmitError(
                    details = details,
                    errorType = InviteErrorType.PasswordMismatch,
                )
            return
        }

        viewModelScope.launch {
            _state.value = InviteRegistrationUiState.Submitting(details)

            try {
                val result = inviteRepository.claimInvite(serverUrl, inviteCode, password)

                serverConfig.setServerUrl(ServerUrl(serverUrl))

                authSession.saveAuthTokens(
                    access = result.accessToken,
                    refresh = result.refreshToken,
                    sessionId = result.sessionId,
                    userId = result.userId,
                )

                userRepository.saveUser(result.user)

                _state.value = InviteRegistrationUiState.Submitted
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorBus.emit(e)
                _state.value = InviteRegistrationUiState.SubmitError(details, e.toInviteErrorType())
            }
        }
    }

    /** Clear a submission error and return to [InviteRegistrationUiState.Ready]. */
    fun clearError() {
        val current = _state.value
        if (current is InviteRegistrationUiState.SubmitError) {
            _state.value = InviteRegistrationUiState.Ready(current.details)
        }
    }

    private fun currentDetails(): InviteDetails? =
        when (val s = _state.value) {
            is InviteRegistrationUiState.Ready -> s.details
            is InviteRegistrationUiState.SubmitError -> s.details
            is InviteRegistrationUiState.Submitting -> s.details
            else -> null
        }
}

@Suppress("CyclomaticComplexMethod")
private fun Exception.toInviteErrorType(): InviteErrorType {
    val msg = message?.lowercase() ?: ""
    val causeMsg = cause?.message?.lowercase() ?: ""
    val fullMsg = "$msg $causeMsg"

    return when {
        msg.contains("already claimed") ||
            msg.contains("claimed") ||
            msg.contains("expired") ||
            (msg.contains("invalid") && msg.contains("invite")) -> {
            InviteErrorType.InviteInvalid
        }

        fullMsg.contains("connection refused") ||
            fullMsg.contains("econnrefused") ||
            fullMsg.contains("timeout") ||
            fullMsg.contains("timed out") ||
            fullMsg.contains("unable to resolve host") ||
            fullMsg.contains("unknown host") -> {
            InviteErrorType.NetworkError(cause?.message ?: message)
        }

        fullMsg.contains("network") ||
            fullMsg.contains("connect") ||
            fullMsg.contains("socket") -> {
            InviteErrorType.NetworkError(cause?.message ?: message)
        }

        fullMsg.contains("500") ||
            fullMsg.contains("502") ||
            fullMsg.contains("503") ||
            fullMsg.contains("504") -> {
            InviteErrorType.ServerError("Server error. Please try again later.")
        }

        else -> {
            InviteErrorType.ServerError(message ?: "Unknown error")
        }
    }
}
