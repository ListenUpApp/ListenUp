package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.error.AuthError

/**
 * Server-side throwable wrapping a typed [AuthError] contract value.
 *
 * The contract layer in commonMain stays free of `Throwable` — `AuthError` is
 * pure `@Serializable` data, the wire shape, nothing more. This wrapper is the
 * JVM-internal mechanism for propagating that data up to the route layer, where
 * Phase D's RPC exception interceptor unwraps it to send the inner [AuthError]
 * over the wire.
 */
class AuthException(
    val error: AuthError,
) : RuntimeException(error::class.simpleName)
