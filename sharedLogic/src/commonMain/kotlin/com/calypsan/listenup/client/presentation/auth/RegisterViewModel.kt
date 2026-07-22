package com.calypsan.listenup.client.presentation.auth

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.usecase.auth.RegisterUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the registration screen.
 *
 * Delegates to [RegisterUseCase] and folds the typed result. On
 * success the use case has already pinned the AuthState transition
 * (PendingApproval or Authenticated); navigation runs off the new
 * state without explicit screen-side routing.
 */
class RegisterViewModel(
    private val registerUseCase: RegisterUseCase,
) : ViewModel() {
    private var closed = false

    override fun onCleared() {
        super.onCleared()
        close()
    }

    /**
     * Cancels this ViewModel's coroutines. Idempotent. Android reaches it via [onCleared] when the
     * `ViewModelStore` clears the entry; iOS has no store, so the screen's wrapper calls it from its
     * `isolated deinit` (#1192) — else viewModelScope streams/one-shots orphan when the screen goes.
     */
    fun close() {
        if (closed) return
        closed = true
        viewModelScope.cancel()
    }

    val state: StateFlow<RegisterUiState>
        field = MutableStateFlow<RegisterUiState>(RegisterUiState.Idle)

    fun onRegisterSubmit(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ) {
        viewModelScope.launch {
            state.value = RegisterUiState.Loading
            state.value =
                when (val result = registerUseCase(email, password, firstName, lastName)) {
                    is AppResult.Success -> {
                        logger.info { "Registration succeeded with outcome=${result.data}" }
                        RegisterUiState.Success
                    }

                    is AppResult.Failure -> {
                        logger.warn { "Registration failed: ${result.error}" }
                        RegisterUiState.Error(result.error.toUserMessage())
                    }
                }
        }
    }

    fun clearError() {
        state.value = RegisterUiState.Idle
    }
}

private fun AppError.toUserMessage(): String =
    when (this) {
        is AuthError.EmailAlreadyExists -> "That email is already registered."
        is AuthError.RegistrationDisabled -> "Registration is closed on this server."
        is AuthError.SetupRequired -> "Server needs initial setup before registration."
        is AuthError.WeakPassword -> "That password doesn't meet the policy (${reason.name.lowercase()})."
        is AuthError.RateLimited -> "Too many attempts; try again in ${retryAfterSeconds}s."
        is ValidationError -> message
        is InternalError -> "Something went wrong. Please try again."
        else -> "Registration failed. Please try again."
    }
