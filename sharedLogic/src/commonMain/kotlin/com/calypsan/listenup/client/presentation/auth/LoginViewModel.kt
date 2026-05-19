package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.usecase.auth.LoginUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the login screen.
 *
 * Thin coordinator that delegates to [LoginUseCase] and folds the typed
 * [AppResult] over the contract's [AppError] hierarchy. Failures map
 * exhaustively — no `classifyByMessage` string-matching from the REST era,
 * just a `when` over real types.
 */
class LoginViewModel(
    private val loginUseCase: LoginUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onLoginSubmit(
        email: String,
        password: String,
    ) {
        viewModelScope.launch {
            _state.value = LoginUiState.Loading
            _state.value =
                when (val result = loginUseCase(email, password)) {
                    is AppResult.Success -> LoginUiState.Success
                    is AppResult.Failure -> LoginUiState.Error(result.error.toLoginErrorType())
                }
        }
    }

    fun clearError() {
        if (_state.value is LoginUiState.Error) {
            _state.value = LoginUiState.Idle
        }
    }
}

private fun AppError.toLoginErrorType(): LoginErrorType =
    when (this) {
        is AuthError.InvalidCredentials,
        is AuthError.AccountDenied,
        is AuthError.PendingApproval,
        -> LoginErrorType.InvalidCredentials

        is AuthError.RateLimited -> LoginErrorType.ServerError("Too many attempts; try again in ${retryAfterSeconds}s.")

        is AuthError.SessionExpired,
        is AuthError.SessionNotFound,
        is AuthError.InvalidRefreshToken,
        -> LoginErrorType.ServerError(null)

        is ValidationError -> LoginErrorType.ValidationError(field())

        is InternalError -> LoginErrorType.NetworkError(null)

        else -> LoginErrorType.ServerError(null)
    }

private fun ValidationError.field(): LoginField =
    when {
        message.contains("email", ignoreCase = true) -> LoginField.EMAIL
        message.contains("password", ignoreCase = true) -> LoginField.PASSWORD
        else -> LoginField.EMAIL
    }
