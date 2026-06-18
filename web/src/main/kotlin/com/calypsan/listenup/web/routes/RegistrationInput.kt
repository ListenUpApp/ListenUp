package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import io.ktor.http.Parameters

/** User-facing message when the email/displayName/password fail [RegisterRequest]'s validation. */
internal const val INVALID_REGISTRATION_INPUT: String =
    "Enter a valid email, a display name, and a password of at least 8 characters."

/**
 * Build a [RegisterRequest] from posted form params, returning null when the contract-level
 * `init` validation (password length, non-blank display name) rejects the input. Construction
 * is non-suspending, so wrapping it in [runCatching] is safe.
 */
internal fun parseRegisterRequest(params: Parameters): RegisterRequest? =
    runCatching {
        RegisterRequest(
            email = params["email"].orEmpty(),
            password = params["password"].orEmpty(),
            displayName = params["displayName"].orEmpty(),
        )
    }.getOrNull()
