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
