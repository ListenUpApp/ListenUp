package com.calypsan.listenup.client.presentation.error

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError

private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409

/**
 * Translates a typed [AppError] into a user-facing display string.
 *
 * Lives in the presentation layer because the message-per-subtype mapping is a UI
 * decision — domain code should propagate the typed error and let presentation render it.
 *
 * Subsequent slices extend the `when` with branches for newly-encountered subtypes;
 * the `else` branch falls through to the body-level `AppError.message` constant per
 * the unified error convention (see `feedback_apperror_message_convention.md`).
 *
 * Use this in ViewModel `Failure` branches when constructing UI error states:
 *
 * ```kotlin
 * is AppResult.Failure -> _state.value = State.Error(userMessageFor(result.error))
 * ```
 *
 * For ErrorBus emissions, emit the typed `result.error` directly — the global snackbar
 * has its own translation logic; presentation translation is for in-screen error states.
 */
fun userMessageFor(error: AppError): String =
    when (error) {
        is TransportError.NetworkUnavailable -> {
            "Can't reach the server. Check your connection."
        }

        is TransportError.Timeout -> {
            "The request took too long. Please try again."
        }

        is TransportError.Server4xx -> {
            when (error.statusCode) {
                HTTP_CONFLICT -> "That resource is in use or already exists."
                HTTP_FORBIDDEN -> "You don't have permission to do that."
                HTTP_NOT_FOUND -> "Not found."
                else -> error.message
            }
        }

        is AuthError.SessionExpired -> {
            "Your session expired. Please sign in again."
        }

        else -> {
            error.message
        }
    }
