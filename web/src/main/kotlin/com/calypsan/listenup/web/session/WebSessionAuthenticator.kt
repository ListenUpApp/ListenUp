package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.loopback.LoopbackAuthClient
import kotlinx.coroutines.sync.withLock

/**
 * Returns a non-expired access token for a [WebSession], refreshing through the loopback
 * client when needed. Refresh is **single-flighted** per session via [WebSession.refreshMutex]:
 * the first caller rotates the token while the rest wait, then everyone re-checks and reuses
 * the freshly-rotated token. This prevents two concurrent requests from presenting the same
 * (rotating) refresh token and tripping the server's replay/family-revoke.
 *
 * @param clock injectable time source (millis) so the freshness boundary is testable.
 * @param skewMs treat a token as expired this long before its real expiry.
 */
class WebSessionAuthenticator(
    private val loopback: LoopbackAuthClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val skewMs: Long = DEFAULT_SKEW_MS,
) {
    /** A valid access token, or `null` when refresh fails (caller should redirect to /login). */
    suspend fun freshAccessToken(session: WebSession): AccessToken? {
        if (!isExpired(session)) return session.accessToken
        return session.refreshMutex.withLock {
            if (!isExpired(session)) {
                session.accessToken
            } else {
                when (val result = loopback.refresh(RefreshRequest(session.refreshToken))) {
                    is AppResult.Success -> {
                        val rotated = result.data
                        session.accessToken = rotated.accessToken
                        session.refreshToken = rotated.refreshToken
                        session.accessExpiresAt = rotated.accessTokenExpiresAt
                        rotated.accessToken
                    }
                    is AppResult.Failure -> {
                        null
                    }
                }
            }
        }
    }

    private fun isExpired(session: WebSession): Boolean = clock() >= session.accessExpiresAt - skewMs

    private companion object {
        const val DEFAULT_SKEW_MS = 30_000L
    }
}
