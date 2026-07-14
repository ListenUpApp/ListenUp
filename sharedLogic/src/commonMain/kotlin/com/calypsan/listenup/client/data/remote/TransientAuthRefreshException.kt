package com.calypsan.listenup.client.data.remote

/**
 * Signals that a token refresh during a handshake-401 heal failed **transiently** — a network drop,
 * a timeout, or a 5xx — rather than because the refresh token was server-confirmed invalid.
 *
 * The distinction is the whole point of the never-stranded promise (C5): a network blip while healing
 * a 401 must NOT be misread as session death and log the user out. [RpcProxyCache] throws this instead
 * of re-raising the original handshake-401 (which
 * [com.calypsan.listenup.client.core.error.ErrorMapper] would map to `SessionExpired` → logout), so
 * the boundary surfaces a **retryable** `TransportError` and the session is kept. A genuinely dead
 * refresh token (an explicit 401/`InvalidRefreshToken` from the refresh endpoint) still surfaces as
 * `SessionExpired`.
 */
internal class TransientAuthRefreshException(
    cause: Throwable,
) : Exception("Token refresh transiently failed during a 401 heal — session kept, retry.", cause)
