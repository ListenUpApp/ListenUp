package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The outcome of a handshake-401 recovery attempt (C5). The three cases drive whether the session
 * survives — the difference between a network blip and a real logout.
 */
internal enum class AuthRecoveryOutcome {
    /** A fresh token is in place — retry the handshake. */
    Refreshed,

    /** The refresh failed transiently (network / timeout / 5xx) — KEEP the session, surface retryable. */
    Transient,

    /** The refresh token is server-confirmed dead — lapse the session (surface SessionExpired). */
    SessionInvalid,
}

/**
 * Recovers RPC authentication when the `/api/rpc/authed` WebSocket handshake is rejected with 401 —
 * the bearer token that authorized the upgrade expired. It refreshes the token via the shared
 * [RefreshAccessToken] seam and rebuilds the request client so its Bearer provider re-reads the new
 * token (the streaming client is deliberately spared via [ApiClientFactory.invalidateRequestClientOnly]).
 *
 * [RpcProxyCache.retryAfterAuthRefresh] wraps [refreshAndRebuild] in `withTimeoutOrNull(timeout)` so
 * a caller can give up on its own budget. That is safe to do here specifically because
 * [RefreshAccessToken] (backed by [com.calypsan.listenup.client.data.repository.AuthRepositoryImpl])
 * already runs its own rotation on a scope independent of whichever caller invokes it — a caller
 * abandoning its wait never cancels the underlying refresh, only its own `.await()` of it. The
 * cancellation-safety invariant lives THERE, once, rather than being re-implemented at every call
 * site; see that class's KDoc for the full reasoning.
 */
internal interface RpcAuthRecovery {
    /** Classifies the refresh so a transient failure can't be mistaken for session death (C5). */
    suspend fun refreshAndRebuild(): AuthRecoveryOutcome

    /** No-op recovery for the unauthenticated `/api/rpc/public` mount — it must never trigger a refresh. */
    object None : RpcAuthRecovery {
        override suspend fun refreshAndRebuild(): AuthRecoveryOutcome = AuthRecoveryOutcome.SessionInvalid
    }
}

internal class RpcAuthRecoveryImpl(
    private val authSession: AuthSession,
    private val refreshAccessToken: RefreshAccessToken,
    private val apiClientFactory: ApiClientFactory,
) : RpcAuthRecovery {
    private val mutex = Mutex()

    /**
     * Serializes the classify-and-rebuild step (not the refresh itself — [refreshAccessToken]
     * dedupes concurrent rotations on its own) so two callers coalescing onto the same refresh
     * outcome don't both race [ApiClientFactory.invalidateRequestClientOnly] /
     * [AuthSession.clearSessionCredentials]. A caller cancelling its own wait here (via the
     * `withTimeoutOrNull` in [RpcProxyCache.retryAfterAuthRefresh]) only abandons its own
     * `.await()` inside [refreshAccessToken] — the rotation itself keeps running independently; see
     * that method's KDoc.
     */
    override suspend fun refreshAndRebuild(): AuthRecoveryOutcome =
        mutex.withLock {
            // The rotated tokens (on success) are persisted inside the single-flight refresh (C1); here
            // we only classify the outcome and rebuild the request client on success.
            when (val result = refreshAccessToken()) {
                is AppResult.Success -> {
                    // Rebuild ONLY the request client so its Bearer provider re-reads the refreshed
                    // token; the long-lived streaming client is untouched.
                    apiClientFactory.invalidateRequestClientOnly()
                    AuthRecoveryOutcome.Refreshed
                }

                is AppResult.Failure -> {
                    when (result.error) {
                        // Server-confirmed dead refresh token → soft-clear so state lands in
                        // SessionLapsed and the boundary surfaces SessionExpired.
                        is AuthError.SessionExpired, is AuthError.InvalidRefreshToken -> {
                            authSession.clearSessionCredentials()
                            AuthRecoveryOutcome.SessionInvalid
                        }

                        // Network / timeout / 5xx / internal — NOT session death. Keep the session.
                        else -> {
                            AuthRecoveryOutcome.Transient
                        }
                    }
                }
            }
        }
}
