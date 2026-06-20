@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.UserId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Mints an [AuthSession] (refresh-session row + access JWT) for an already-created/resolved user. */
class SessionIssuer(
    private val sessions: SessionService,
    private val jwt: JwtConfiguration,
    private val clock: Clock = Clock.System,
) {
    internal suspend fun issue(
        user: AuthUser,
        label: String?,
        deviceInfo: DeviceInfo? = null,
        userAgent: String? = null,
    ): AuthSession {
        val userId = UserId(user.id)
        val role = user.role.toContract()
        val issued = sessions.createSession(userId, label = label, deviceInfo = deviceInfo, userAgent = userAgent)
        val accessJwt = jwt.issue(userId = userId, sessionId = issued.sessionId, role = role)
        val expiresAt = (clock.now() + jwt.accessTokenTtl).toEpochMilliseconds()
        return AuthSession(
            accessToken = AccessToken(accessJwt),
            accessTokenExpiresAt = expiresAt,
            refreshToken = issued.refreshToken,
            refreshTokenExpiresAt = issued.expiresAt,
            sessionId = issued.sessionId,
            user = user.toContract(),
        )
    }
}
