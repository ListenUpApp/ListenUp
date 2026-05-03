package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.usecase.auth.SetupUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the root user setup screen.
 *
 * Validation runs client-side before submit so we can map field-specific
 * `ValidationError` failures from [SetupUseCase] back to the right
 * [SetupField]. On success the use case persists tokens (flipping
 * `AuthState` to `Authenticated`) and the screen finishes via the
 * resulting nav transition.
 */
class SetupViewModel(
    private val setupUseCase: SetupUseCase,
    private val authSession: AuthSession,
) : ViewModel() {
    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onSetupSubmit(
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        passwordConfirm: String,
    ) {
        if (password != passwordConfirm) {
            _state.value = SetupUiState.Error(SetupErrorType.ValidationError(SetupField.PASSWORD_CONFIRM))
            return
        }

        viewModelScope.launch {
            _state.value = SetupUiState.Loading
            val result = setupUseCase(email, password, firstName, lastName)
            _state.value =
                when (result) {
                    is AppResult.Success -> SetupUiState.Success
                    is AppResult.Failure -> handleFailure(result.error)
                }
        }
    }

    private suspend fun handleFailure(error: AppError): SetupUiState.Error {
        val type =
            when (error) {
                is AuthError.SetupAlreadyComplete -> SetupErrorType.AlreadyConfigured
                is AuthError.WeakPassword -> SetupErrorType.ValidationError(SetupField.PASSWORD)
                is ValidationError -> SetupErrorType.ValidationError(error.field())
                is InternalError -> SetupErrorType.ServerError
                else -> SetupErrorType.ServerError
            }
        if (type == SetupErrorType.AlreadyConfigured) {
            // Server already configured — refresh auth state so navigation routes the user
            // to login instead of stranding them on the setup screen.
            authSession.checkServerStatus()
        }
        return SetupUiState.Error(type)
    }

    fun clearError() {
        if (_state.value is SetupUiState.Error) {
            _state.value = SetupUiState.Idle
        }
    }
}

/**
 * Best-effort heuristic to map a generic [ValidationError] message back to
 * the form field it referred to. The use case emits readable messages
 * ("First name is required", "Please enter a valid email address", …) so
 * this is enough for highlighting the right input. If the contract grows a
 * structured field-validation type later, swap this for a direct lookup.
 */
private fun ValidationError.field(): SetupField =
    when {
        message.contains("first", ignoreCase = true) -> SetupField.FIRST_NAME
        message.contains("last", ignoreCase = true) -> SetupField.LAST_NAME
        message.contains("email", ignoreCase = true) -> SetupField.EMAIL
        message.contains("password", ignoreCase = true) -> SetupField.PASSWORD
        else -> SetupField.EMAIL
    }
