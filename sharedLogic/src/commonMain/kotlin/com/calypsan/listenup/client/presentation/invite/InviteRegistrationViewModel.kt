package com.calypsan.listenup.client.presentation.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
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
    private val serverUrl: String,
    private val inviteCode: String,
    private val errorBus: ErrorBus,
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

            when (val result = inviteRepository.getInviteDetails(serverUrl, inviteCode)) {
                is AppResult.Success -> {
                    val details = result.data
                    _state.value =
                        if (!details.valid) {
                            InviteRegistrationUiState.Invalid(
                                "This invite is no longer valid. It may have already been used or expired.",
                            )
                        } else {
                            InviteRegistrationUiState.Ready(details)
                        }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    _state.value = InviteRegistrationUiState.LoadError(result.error.message)
                }
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

            serverConfig.setServerUrl(ServerUrl(serverUrl))
            when (val result = inviteRepository.claimInvite(serverUrl, inviteCode, password)) {
                is AppResult.Success -> {
                    _state.value = InviteRegistrationUiState.Submitted
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    _state.value =
                        InviteRegistrationUiState.SubmitError(
                            details,
                            result.error.toInviteErrorType(),
                        )
                }
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

private fun AppError.toInviteErrorType(): InviteErrorType =
    when (this) {
        is TransportError.NetworkUnavailable -> InviteErrorType.NetworkError(debugInfo)
        is TransportError.Timeout -> InviteErrorType.NetworkError(debugInfo)
        is TransportError.Server4xx -> InviteErrorType.InviteInvalid
        is TransportError.Server5xx -> InviteErrorType.ServerError(message)
        else -> InviteErrorType.ServerError(message)
    }
