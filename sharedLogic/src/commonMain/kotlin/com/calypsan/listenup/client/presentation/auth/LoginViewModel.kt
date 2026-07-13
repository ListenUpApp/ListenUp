package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.ValidationField
import com.calypsan.listenup.client.domain.usecase.auth.LoginUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val state: StateFlow<LoginUiState>
        field = MutableStateFlow<LoginUiState>(LoginUiState.Idle)

    fun onLoginSubmit(
        email: String,
        password: String,
    ) {
        viewModelScope.launch {
            state.value = LoginUiState.Loading
            state.value =
                when (val result = loginUseCase(email, password)) {
                    is AppResult.Success -> LoginUiState.Success
                    is AppResult.Failure -> LoginUiState.Error(result.error.toLoginErrorType())
                }
        }
    }

    fun clearError() {
        if (state.value is LoginUiState.Error) {
            state.value = LoginUiState.Idle
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
    when (field) {
        ValidationField.PASSWORD -> LoginField.PASSWORD
        ValidationField.EMAIL -> LoginField.EMAIL
        else -> LoginField.EMAIL
    }
