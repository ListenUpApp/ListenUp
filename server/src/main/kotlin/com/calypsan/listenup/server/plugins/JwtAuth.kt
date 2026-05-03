package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.JwtVerificationException
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.UserPrincipal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.application.Application as KtorApplication

/** Name of the bearer-JWT provider — referenced by route handlers. */
const val JWT_PROVIDER = "jwt"

/**
 * Installs a `bearer` provider named [JWT_PROVIDER] that verifies the access
 * JWT, then runs an `isLive` check on the session row before handing back a
 * [UserPrincipal]. A revoked or expired session can't sneak through on a
 * still-valid JWT — the access window is bounded by the JWT's TTL (≤15m).
 */
fun KtorApplication.installJwtAuth(
    jwt: JwtConfiguration,
    sessions: SessionService,
) {
    install(Authentication) {
        bearerJwt(jwt, sessions)
    }
}

private fun AuthenticationConfig.bearerJwt(
    jwt: JwtConfiguration,
    sessions: SessionService,
) {
    bearer(JWT_PROVIDER) {
        authenticate { credential ->
            val claims =
                try {
                    jwt.verify(credential.token)
                } catch (_: JwtVerificationException) {
                    return@authenticate null
                }
            if (!sessions.isLive(claims.sessionId)) return@authenticate null
            UserPrincipal(claims.userId, claims.sessionId, claims.role)
        }
    }
}

/**
 * Convenience accessor — returns the authenticated principal, or null if the
 * route wasn't gated by [JWT_PROVIDER]. Route handlers prefer this over
 * `call.principal<UserPrincipal>()` so the type is unambiguous at the call site.
 */
fun ApplicationCall.userPrincipalOrNull(): UserPrincipal? = principal<UserPrincipal>()
