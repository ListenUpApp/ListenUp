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
 * A [Mutex] serializes concurrent refreshes so they don't race the token store or stampede the
 * refresh endpoint; it does not deduplicate them into a single call across factories (each 401
 * still enters, and a later one may refresh an already-fresh token). That is acceptable — refresh
 * is idempotent and cheap relative to the failure it recovers.
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
