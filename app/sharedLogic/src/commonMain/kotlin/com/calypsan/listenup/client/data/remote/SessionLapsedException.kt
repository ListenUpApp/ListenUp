package com.calypsan.listenup.client.data.remote

/**
 * Signals that a token refresh during a handshake heal came back **server-confirmed dead** — the
 * refresh token is gone, not merely unreachable — so the session has lapsed and the reader has to
 * sign in again.
 *
 * The counterpart to [TransientAuthRefreshException], and the reason both exist rather than one:
 * the difference between "your network blinked" and "your session ended" is the whole of whether a
 * device keeps working offline or sends someone to a login screen, and it cannot be guessed.
 *
 * [RpcProxyCache] throws this rather than re-raising the original handshake failure, which is what
 * it used to do. That older shape asked [com.calypsan.listenup.client.core.error.ErrorMapper] to
 * re-derive the meaning by finding `"401"` in a Ktor exception message — fine on an engine that
 * upgrades over a real HTTP response, and silently wrong on the browser, where the message carries
 * no status at all (see [handshakeStatusIsVisible]). There the re-raised exception mapped to a
 * generic transport fault, so a reader whose session had ended was told their network was down and
 * was never sent to sign in. A refresh outcome already knows which case it is; carrying it in the
 * type keeps the answer instead of throwing it away and guessing at the boundary.
 */
internal class SessionLapsedException(
    cause: Throwable,
) : Exception("The refresh token is server-confirmed dead — the session has lapsed, sign in again.", cause)
