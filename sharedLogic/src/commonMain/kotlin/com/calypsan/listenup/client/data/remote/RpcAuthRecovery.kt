package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.domain.repository.AuthSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recovers RPC authentication when the `/api/rpc/authed` WebSocket handshake is rejected with 401 —
 * the bearer token that authorized the upgrade expired. It refreshes the token via the shared
 * [RefreshAccessToken] seam and rebuilds the request client so its Bearer provider re-reads the new
 * token (the streaming/SSE client is deliberately spared via [ApiClientFactory.invalidateRequestClientOnly]).
 *
 * A [Mutex] serializes concurrent refreshes so they don't race the token store or stampede the
 * refresh endpoint; it does not deduplicate them into a single call across factories (each 401
 * still enters, and a later one may refresh an already-fresh token). That is acceptable — refresh
 * is idempotent and cheap relative to the failure it recovers.
 */
internal interface RpcAuthRecovery {
    /** @return true if a fresh token is now in place (retry the handshake); false if re-auth is required. */
    suspend fun refreshAndRebuild(): Boolean

    /** No-op recovery for the unauthenticated `/api/rpc/public` mount — it must never trigger a refresh. */
    object None : RpcAuthRecovery {
        override suspend fun refreshAndRebuild(): Boolean = false
    }
}

internal class RpcAuthRecoveryImpl(
    private val authSession: AuthSession,
    private val refreshAccessToken: RefreshAccessToken,
    private val apiClientFactory: ApiClientFactory,
) : RpcAuthRecovery {
    private val mutex = Mutex()

    override suspend fun refreshAndRebuild(): Boolean =
        mutex.withLock {
            // refreshAuthTokens saves the new tokens into AuthSession and returns them (null on failure,
            // clearing tokens on an invalid refresh token so the global auth observer routes to login).
            val refreshed = refreshAuthTokens(authSession, refreshAccessToken) != null
            if (refreshed) {
                // Rebuild ONLY the request client so its Bearer provider re-reads the refreshed token;
                // the long-lived streaming/SSE client is untouched.
                apiClientFactory.invalidateRequestClientOnly()
            }
            refreshed
        }
}
