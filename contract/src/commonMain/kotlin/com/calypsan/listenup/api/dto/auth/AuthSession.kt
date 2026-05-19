package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Returned to the client on every successful login / register / refresh.
 *
 * `accessToken` is a 15-minute JWT (HS256). `refreshToken` is an opaque
 * 32-byte secret valid for ~30 days, sliding on use. Both expiry fields are
 * unix millis.
 */
@Serializable
data class AuthSession(
    @SerialName("accessToken")
    val accessToken: AccessToken,
    val accessTokenExpiresAt: Long,
    val refreshToken: RefreshToken,
    val refreshTokenExpiresAt: Long,
    val sessionId: SessionId,
    val user: User,
)
