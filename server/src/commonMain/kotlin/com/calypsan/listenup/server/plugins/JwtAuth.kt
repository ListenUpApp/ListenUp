package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.JwtVerificationException
import com.calypsan.listenup.server.auth.SessionLiveness
import com.calypsan.listenup.server.auth.UserPrincipal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import io.ktor.server.application.Application as KtorApplication

private val logger = KotlinLogging.logger("com.calypsan.listenup.server.plugins.JwtAuth")

/**
 * Query parameter carrying the access token when the client cannot send an `Authorization` header.
 *
 * Mirrored client-side by `ACCESS_TOKEN_QUERY_PARAM` in
 * `app/sharedLogic/.../client/data/remote/WsUpgradeHeaders.kt` — the two must agree.
 */
private const val ACCESS_TOKEN_QUERY_PARAM = "access_token"

/**
 * RFC 7235 `token68`. Ktor's [HttpAuthHeader.Single] rejects anything outside this alphabet by
 * throwing, and a malformed query parameter is a client error, not a server one — so the value is
 * screened here and an unusable one simply produces no credential (→ 401), never a 500.
 */
private val TOKEN68 = Regex("[a-zA-Z0-9\\-._~+/]+=*")

/** Logs an auth rejection at WARN. [reason] must never contain the token or any secret. */
internal fun logJwtRejection(reason: String) {
    logger.warn { "auth rejected: $reason" }
}

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
    sessionLiveness: SessionLiveness,
) {
    install(Authentication) {
        bearerJwt(jwt, sessionLiveness)
    }
}

private fun AuthenticationConfig.bearerJwt(
    jwt: JwtConfiguration,
    sessionLiveness: SessionLiveness,
) {
    bearer(JWT_PROVIDER) {
        // Header first, query parameter second. The order is the whole design: every native client
        // (JVM, linuxX64, iOS, Android) keeps presenting its credential exactly as it does today,
        // and only a caller that COULDN'T send a header falls through to the second read. The
        // browser is that caller — `new WebSocket(url, protocols)` takes no headers, so Ktor's JS
        // engine drops the one the `Auth` plugin wrote and the upgrade arrives bare.
        //
        // Nothing below this line changes: the token still goes through `jwt.verify` and the
        // session-liveness check. This decides only where the credential is READ FROM.
        authHeader { call ->
            call.request.parseAuthorizationHeader() ?: call.accessTokenQueryCredential()
        }
        authenticate { credential ->
            val claims =
                try {
                    jwt.verify(credential.token)
                } catch (_: JwtVerificationException) {
                    logJwtRejection("token verification failed")
                    return@authenticate null
                }
            if (!sessionLiveness.isLive(claims.sessionId)) {
                logJwtRejection("session no longer live for sessionId=${claims.sessionId}")
                return@authenticate null
            }
            UserPrincipal(claims.userId, claims.sessionId, claims.role)
        }
    }
}

/**
 * The `?access_token=` credential, or null when the request carries none (or one whose shape can't
 * be a bearer blob). Only ever consulted when the `Authorization` header is absent.
 */
private fun ApplicationCall.accessTokenQueryCredential(): HttpAuthHeader? =
    request.queryParameters[ACCESS_TOKEN_QUERY_PARAM]
        ?.takeIf { it.matches(TOKEN68) }
        ?.let { HttpAuthHeader.Single(AuthScheme.Bearer, it) }

/**
 * Convenience accessor — returns the authenticated principal, or null if the
 * route wasn't gated by [JWT_PROVIDER]. Route handlers prefer this over
 * `call.principal<UserPrincipal>()` so the type is unambiguous at the call site.
 */
fun ApplicationCall.userPrincipalOrNull(): UserPrincipal? = principal<UserPrincipal>()
