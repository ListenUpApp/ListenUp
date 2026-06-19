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
 *
 * The principal's [UserPrincipal.role] is [UserRole.ROOT] by default — the
 * historic behaviour, which sidesteps every access gate. Tests that exercise a
 * book-level access boundary (a member who can't reach a private book) pass a
 * `roleResolver` mapping the user id to the role the seeded user actually holds,
 * so the route's [com.calypsan.listenup.server.api.BookAccessPolicy] gate runs
 * against a real `(userId, role)` pair instead of an all-bypassing ROOT.
 */
class TestAuthProvider(
    config: Config,
) : AuthenticationProvider(config) {
    private val defaultUserId: String = config.defaultUserId
    private val roleResolver: (String) -> UserRole = config.roleResolver

    class Config internal constructor(
        name: String,
        val defaultUserId: String,
        val roleResolver: (String) -> UserRole,
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
                role = roleResolver(userId),
            ),
        )
    }
}

/**
 * Installs a [TestAuthProvider] under [JWT_PROVIDER] so `authenticate(JWT_PROVIDER)`
 * route blocks resolve a [UserPrincipal] in tests without a real token.
 *
 * [roleResolver] maps the authenticated user id to its [UserRole]; the default
 * grants every principal [UserRole.ROOT] (the all-bypassing behaviour most route
 * tests rely on). Override it to test access gates with member/admin principals.
 */
fun AuthenticationConfig.testAuth(
    defaultUserId: String = "test-user",
    roleResolver: (String) -> UserRole = { UserRole.ROOT },
) {
    register(TestAuthProvider(TestAuthProvider.Config(JWT_PROVIDER, defaultUserId, roleResolver)))
}
