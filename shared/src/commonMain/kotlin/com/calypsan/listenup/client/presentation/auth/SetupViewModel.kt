package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the root user setup screen.
 *
 * Handles validation and submission of the initial admin account creation.
 * On success, stores auth tokens which triggers AuthState.Authenticated,
 * causing automatic navigation to the Library screen.
 */
class SetupViewModel(
    private val authRepository: AuthRepository,
    private val authSession: AuthSession,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /**
     * Submit the setup form to create the root user.
     *
     * Performs client-side validation before making the network request.
     * On success, stores tokens and navigation happens automatically via AuthState.
     */
    fun onSetupSubmit(
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        passwordConfirm: String,
    ) {
        // Client-side validation
        val trimmedFirstName = firstName.trim()
        val trimmedLastName = lastName.trim()
        val trimmedEmail = email.trim()

        if (trimmedFirstName.isBlank()) {
            _state.value = SetupUiState.Error(SetupErrorType.ValidationError(SetupField.FIRST_NAME))
            return
        }

        if (trimmedLastName.isBlank()) {
            _state.value = SetupUiState.Error(SetupErrorType.ValidationError(SetupField.LAST_NAME))
            return
        }

        if (!isValidEmail(trimmedEmail)) {
            _state.value = SetupUiState.Error(SetupErrorType.ValidationError(SetupField.EMAIL))
            return
        }

        if (password.length < PASSWORD_MIN) {
            _state.value = SetupUiState.Error(SetupErrorType.ValidationError(SetupField.PASSWORD))
            return
        }

        if (password != passwordConfirm) {
            _state.value = SetupUiState.Error(SetupErrorType.ValidationError(SetupField.PASSWORD_CONFIRM))
            return
        }

        // Submit to server
        viewModelScope.launch {
            _state.value = SetupUiState.Loading

            try {
                val result =
                    authRepository.setup(
                        email = trimmedEmail,
                        password = password,
                        firstName = trimmedFirstName,
                        lastName = trimmedLastName,
                    )

                // Store tokens - this triggers AuthState.Authenticated
                authSession.saveAuthTokens(
                    access = result.accessToken,
                    refresh = result.refreshToken,
                    sessionId = result.sessionId,
                    userId = result.userId,
                )

                // Save user data to local database for avatar display
                userRepository.saveUser(result.user)

                _state.value = SetupUiState.Success
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorBus.emit(e)
                val errorType = e.toSetupErrorType()

                // If server is already configured, refresh auth state to navigate to login.
                // This handles the case where setup request timed out but server completed it.
                // Implements the "never stranded" principle - user can proceed to login.
                if (errorType == SetupErrorType.AlreadyConfigured) {
                    authSession.checkServerStatus()
                }

                _state.value = SetupUiState.Error(errorType)
            }
        }
    }

    /** Clear the error state to allow retry. */
    fun clearError() {
        if (_state.value is SetupUiState.Error) {
            _state.value = SetupUiState.Idle
        }
    }

    /**
     * Basic email validation.
     */
    private fun isValidEmail(email: String): Boolean = email.contains("@") && email.contains(".")
}

/**
 * Convert exception to semantic error type.
 */
private fun Exception.toSetupErrorType(): SetupErrorType =
    when {
        message?.contains("already configured", ignoreCase = true) == true -> {
            SetupErrorType.AlreadyConfigured
        }

        message?.contains("network", ignoreCase = true) == true ||
            message?.contains("connection", ignoreCase = true) == true -> {
            SetupErrorType.NetworkError
        }

        else -> {
            SetupErrorType.ServerError
        }
    }
