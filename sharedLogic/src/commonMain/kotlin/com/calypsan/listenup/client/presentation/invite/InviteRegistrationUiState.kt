package com.calypsan.listenup.client.presentation.invite

import com.calypsan.listenup.client.domain.model.InviteDetails

/**
 * UI state for the invite registration screen.
 *
 * Sealed hierarchy — the screen is in exactly one of these states.
 * The load phase (Loading → Ready/Invalid/LoadError) precedes the
 * submit phase (Ready → Submitting → Submitted/SubmitError), so all
 * submit-phase states carry the loaded [InviteDetails].
 */
sealed interface InviteRegistrationUiState {
    /** Fetching invite details from the server. */
    data object Loading : InviteRegistrationUiState

    /** Invite details loaded — form is ready for input. */
    data class Ready(
        val details: InviteDetails,
    ) : InviteRegistrationUiState

    /** Invite is no longer valid (claimed, expired, or not found). */
    data class Invalid(
        val reason: String,
    ) : InviteRegistrationUiState

    /** Failed to load invite details. */
    data class LoadError(
        val message: String,
    ) : InviteRegistrationUiState

    /** Registration submission in flight. */
    data class Submitting(
        val details: InviteDetails,
    ) : InviteRegistrationUiState

    /** Registration succeeded — auth tokens stored, navigation imminent. */
    data object Submitted : InviteRegistrationUiState

    /** Submission failed. */
    data class SubmitError(
        val details: InviteDetails,
        val errorType: InviteErrorType,
    ) : InviteRegistrationUiState
}

/**
 * Types of errors that can occur during invite registration.
 */
sealed interface InviteErrorType {
    /** Password doesn't meet requirements. */
    data class ValidationError(
        val field: InviteField,
    ) : InviteErrorType

    /** Passwords don't match. */
    data object PasswordMismatch : InviteErrorType

    /** Network connection error. */
    data class NetworkError(
        val detail: String?,
    ) : InviteErrorType

    /** Server returned an error. */
    data class ServerError(
        val detail: String?,
    ) : InviteErrorType

    /** Invite is no longer valid. */
    data object InviteInvalid : InviteErrorType
}

/** Fields in the invite registration form that can have validation errors. */
enum class InviteField {
    PASSWORD,
    CONFIRM_PASSWORD,
}
