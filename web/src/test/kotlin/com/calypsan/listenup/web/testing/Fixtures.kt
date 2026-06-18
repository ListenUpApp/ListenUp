package com.calypsan.listenup.web.testing

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus

/** A fully-populated [AuthSession] with a far-future access expiry (treated as fresh in flow tests). */
internal fun sampleAuthSession(): AuthSession =
    AuthSession(
        accessToken = AccessToken("at"),
        accessTokenExpiresAt = 9_999_999_999_999L,
        refreshToken = RefreshToken("rt"),
        refreshTokenExpiresAt = 9_999_999_999_999L,
        sessionId = SessionId("s1"),
        user =
            User(
                id = UserId("u1"),
                email = "a@x",
                displayName = "A",
                role = UserRole.MEMBER,
                status = UserStatus.ACTIVE,
                createdAt = 0L,
            ),
    )
