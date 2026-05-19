package com.calypsan.listenup.client.presentation.auth

/**
 * UI state for the login screen.
 *
 * Sealed hierarchy — the screen is always in exactly one of these states.
 * Shown when the server has users but the app has no valid tokens.
 */
sealed interface LoginUiState {
    /** Initial state — ready for user input. */
    data object Idle : LoginUiState

    /** Login request in progress. */
    data object Loading : LoginUiState

    /**
     * Login completed successfully.
     * Navigation happens automatically via AuthState change.
     */
    data object Success : LoginUiState

    /** Login failed. */
    data class Error(
        val type: LoginErrorType,
    ) : LoginUiState
}

/**
 * Types of errors that can occur during login.
 * Semantic error types — platforms decide how to display them.
 */
sealed interface LoginErrorType {
    /** Invalid credentials (wrong email or password). */
    data object InvalidCredentials : LoginErrorType

    /** Network connection error (can't reach server). */
    data class NetworkError(
        val detail: String? = null,
    ) : LoginErrorType

    /** Server returned an error (500, etc). */
    data class ServerError(
        val detail: String? = null,
    ) : LoginErrorType

    /** Client-side validation error on a specific field. */
    data class ValidationError(
        val field: LoginField,
    ) : LoginErrorType
}

/** Fields in the login form. */
enum class LoginField {
    EMAIL,
    PASSWORD,
}
