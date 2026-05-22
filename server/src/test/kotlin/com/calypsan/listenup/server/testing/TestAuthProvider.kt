package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.parseAuthorizationHeader

/**
 * Test-only [AuthenticationProvider] registered under [JWT_PROVIDER]. It
 * unconditionally authenticates every request with a [UserPrincipal], so
 * route tests can mount auth-gated routes (e.g. [syncRoutes]) without minting
 * a real JWT.
 *
 * The principal's [UserPrincipal.userId] is taken from the request's
 * `Authorization: Bearer <token>` header when present — the token string is
 * used verbatim as the user id — and falls back to [defaultUserId] otherwise.
 * This lets a single test exercise multiple users by sending different bearer
 * tokens (`bearerAuth("u1")` vs `bearerAuth("u2")`), while tests that send no
 * header at all keep authenticating as [defaultUserId].
 */
class TestAuthProvider(
    config: Config,
) : AuthenticationProvider(config) {
    private val defaultUserId: String = config.defaultUserId

    class Config internal constructor(
        name: String,
        val defaultUserId: String,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val bearerToken =
            (context.call.request.parseAuthorizationHeader() as? HttpAuthHeader.Single)
                ?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }
                ?.blob
        val userId = bearerToken ?: defaultUserId
        context.principal(
            UserPrincipal(
                userId = UserId(userId),
                sessionId = SessionId("test-session-$userId"),
                role = UserRole.ROOT,
            ),
        )
    }
}

/**
 * Installs a [TestAuthProvider] under [JWT_PROVIDER] so `authenticate(JWT_PROVIDER)`
 * route blocks resolve a [UserPrincipal] in tests without a real token.
 */
fun AuthenticationConfig.testAuth(defaultUserId: String = "test-user") {
    register(TestAuthProvider(TestAuthProvider.Config(JWT_PROVIDER, defaultUserId)))
}
