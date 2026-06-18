package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import kotlinx.coroutines.sync.Mutex

/**
 * One browser's server-side session, keyed by an opaque cookie id. Holds the rotating
 * refresh token and the cached access token in RAM only (never persisted). [refreshMutex]
 * single-flights token refresh so concurrent requests on the same browser session can't
 * present the same (rotating) refresh token twice and trip replay/family-revoke.
 */
class WebSession(
    val sessionId: SessionId,
    val userId: UserId,
    val role: UserRole,
    @Volatile var accessToken: AccessToken,
    @Volatile var refreshToken: RefreshToken,
    @Volatile var accessExpiresAt: Long,
) {
    val refreshMutex: Mutex = Mutex()
}
