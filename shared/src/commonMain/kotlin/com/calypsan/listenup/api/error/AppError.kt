package com.calypsan.listenup.api.error

import kotlinx.serialization.Serializable

/**
 * Root sealed interface for every error the server returns over the wire.
 * All subclasses are @Serializable and carry a server-issued correlation id
 * that matches the request's MDC `correlationId` and `X-Request-Id` header.
 */
@Serializable
sealed interface AppError {
    val correlationId: String?
}

/**
 * Catch-all for unmapped server-side exceptions. The RPC exception
 * interceptor returns this when an internal error is not a typed
 * AuthError (or future domain error). The stacktrace stays on the server;
 * the client only sees the correlation id.
 */
@Serializable
data class InternalError(
    override val correlationId: String? = null,
) : AppError

/**
 * Input failed validation — surfaces both client-side pre-flight checks
 * (e.g. "email format invalid", "password too short") and server-side
 * `init`-block violations on `@Serializable` requests. `message` is
 * user-facing and field-agnostic; UI consumers display it directly.
 *
 * Distinct from typed domain errors like `AuthError.InvalidCredentials`:
 * `ValidationError` means "your input is malformed," not "the server
 * rejected your credentials."
 */
@Serializable
data class ValidationError(
    val message: String,
    override val correlationId: String? = null,
) : AppError
