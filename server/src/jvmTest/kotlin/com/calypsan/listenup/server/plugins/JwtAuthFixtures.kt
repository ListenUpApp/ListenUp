package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionLiveness
import com.calypsan.listenup.server.auth.SocketTicketStore
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * The shared setup behind both carrier specs — [JwtAuthTest] for `JWT_PROVIDER`'s header and ticket,
 * [CookieCarrierTest] for `BLOB_READ_PROVIDER`'s header and cookie.
 *
 * They stay separate specs, one per provider, because that is how the security boundary reads: each
 * file is a complete statement of what one provider will and will not accept. What they must not
 * have separately is the fixture — two copies of "what a valid token looks like" is how a spec ends
 * up proving something subtly different from its sibling.
 */
internal val testJwt =
    JwtConfiguration(
        secret = "test-secret-that-is-long-enough-32",
        issuer = "listenup-test",
        audience = "listenup-test-clients",
    )

/** The user every fixture token authenticates as — asserted on, so the caller's identity is pinned. */
internal const val FIXTURE_USER_ID = "user-1"

/** A valid access token for [FIXTURE_USER_ID] on [sessionId]. */
internal fun tokenFor(sessionId: String): String =
    testJwt.issue(
        userId = UserId(FIXTURE_USER_ID),
        sessionId = SessionId(sessionId),
        role = UserRole.ADMIN,
    )

/**
 * Boots an app whose only route, `GET /gated`, sits behind [provider] and echoes the authenticated
 * user id — so an assertion can distinguish "reached the handler" from "reached it as the right
 * principal", which a bare 200 cannot.
 *
 * Sessions are always live here: these specs are about which carrier gets read, and the liveness
 * re-check is [JwtAuthTest]'s own subject.
 */
internal fun ApplicationTestBuilder.gatedRoute(
    provider: String,
    socketTickets: SocketTicketStore? = null,
) {
    application {
        installJwtAuth(testJwt, SessionLiveness { true }, socketTickets)
        routing {
            authenticate(provider) {
                get("/gated") { call.respondText(call.userPrincipalOrNull()?.userId?.value ?: "none") }
            }
        }
    }
}
