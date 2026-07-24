package com.calypsan.listenup.client.presentation.auth

/**
 * UI state for the root user setup screen.
 *
 * Sealed hierarchy — the screen is always in exactly one of these states.
 * Shown when the server has no users configured yet.
 */
sealed interface SetupUiState {
    /** Initial state — ready for user input. */
    data object Idle : SetupUiState

    /** Setup request in progress. */
    data object Loading : SetupUiState

    /**
     * Setup completed successfully.
     * Navigation happens automatically via AuthState change.
     */
    data object Success : SetupUiState

    /** Setup failed. */
    data class Error(
        val type: SetupErrorType,
    ) : SetupUiState
}

/**
 * Types of errors that can occur during setup.
 * Semantic error types — platforms decide how to display them.
 */
sealed interface SetupErrorType {
    /** Network connection error. */
    data object NetworkError : SetupErrorType

    /** Server returned an error (500, etc). */
    data object ServerError : SetupErrorType

    /** Client-side validation error on a specific field. */
    data class ValidationError(
        val field: SetupField,
    ) : SetupErrorType

    /** Server is already configured (shouldn't happen in normal flow). */
    data object AlreadyConfigured : SetupErrorType
}

/** Fields in the setup form. */
enum class SetupField {
    FIRST_NAME,
    LAST_NAME,
    EMAIL,
    PASSWORD,
    PASSWORD_CONFIRM,
}
