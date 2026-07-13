package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.ValidationField
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.usecase.auth.SetupUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val state: StateFlow<SetupUiState>
        field = MutableStateFlow<SetupUiState>(SetupUiState.Idle)

    fun onSetupSubmit(
        firstName: String,
        lastName: String,
        email: String,
        password: String,
        passwordConfirm: String,
    ) {
        if (password != passwordConfirm) {
            state.value = SetupUiState.Error(SetupErrorType.ValidationError(SetupField.PASSWORD_CONFIRM))
            return
        }

        viewModelScope.launch {
            state.value = SetupUiState.Loading
            val result = setupUseCase(email, password, firstName, lastName)
            state.value =
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
        if (state.value is SetupUiState.Error) {
            state.value = SetupUiState.Idle
        }
    }
}

/**
 * Map a [ValidationError] to the form field it referred to via the typed
 * [ValidationError.field] discriminator — no substring-matching on the
 * user-facing [ValidationError.message] (which a server reword would silently
 * break). Falls back to [SetupField.EMAIL] when the error carries no field.
 */
private fun ValidationError.field(): SetupField =
    when (field) {
        ValidationField.FIRST_NAME -> SetupField.FIRST_NAME
        ValidationField.LAST_NAME -> SetupField.LAST_NAME
        ValidationField.EMAIL -> SetupField.EMAIL
        ValidationField.PASSWORD -> SetupField.PASSWORD
        else -> SetupField.EMAIL
    }
