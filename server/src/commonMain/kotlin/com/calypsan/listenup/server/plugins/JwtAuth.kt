package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.JwtVerificationException
import com.calypsan.listenup.server.auth.SessionLiveness
import com.calypsan.listenup.server.auth.SocketTicketStore
import com.calypsan.listenup.server.auth.UserPrincipal
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.parsing.ParseException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import io.ktor.server.application.Application as KtorApplication

private val logger = KotlinLogging.logger("com.calypsan.listenup.server.plugins.JwtAuth")

/** Logs an auth rejection at WARN. [reason] must never contain the token or any secret. */
internal fun logJwtRejection(reason: String) {
    logger.warn { "auth rejected: $reason" }
}

/** Name of the bearer-JWT provider — referenced by route handlers. */
const val JWT_PROVIDER = "jwt"

/**
 * Query parameter carrying a single-use [SocketTicketStore] ticket when the request cannot carry an
 * `Authorization` header.
 *
 * That is exactly one caller: a browser opening the RPC WebSocket. The DOM `WebSocket` constructor
 * takes `(url, protocols)` and nothing else, so no header the client writes ever reaches the
 * upgrade. Mirrored client-side by `SOCKET_TICKET_QUERY_PARAM` in
 * `app/sharedLogic/.../data/remote/WsUpgradeHeaders.kt` — the two must agree.
 *
 * ⚠️ A **ticket**, never an access token. A URL reaches a reverse proxy's access log (nginx's
 * `combined` format logs `$request` query string and all), so whatever goes here should be worthless
 * by the time anyone reads it. Accepting a raw JWT as well would quietly re-open exactly that hole,
 * so this provider does not — a JWT presented here is simply not a valid ticket.
 */
private const val SOCKET_TICKET_QUERY_PARAM = "ticket"

/**
 * Cookie carrying the access token for credentials the DOM sends on our behalf.
 *
 * An `<img>` tag cannot set an Authorization header, which is the same shape of problem the
 * socket ticket solves for WebSocket upgrades. The browser writes this itself beside the token it
 * already keeps in origin-scoped localStorage, so it exposes nothing a script on this origin
 * could not already read.
 */
const val ACCESS_TOKEN_COOKIE = "listenup_access"

/**
 * Installs a `bearer` provider named [JWT_PROVIDER] that verifies the access
 * JWT, then runs an `isLive` check on the session row before handing back a
 * [UserPrincipal]. A revoked or expired session can't sneak through on a
 * still-valid JWT — the access window is bounded by the JWT's TTL (≤15m).
 *
 * [socketTickets] is the browser's way in: a ticket in the query redeems to the access token it
 * stands for, which is then verified on exactly the path a header-borne token takes. Null disables
 * the ticket path entirely (no browser client, no query credential accepted).
 */
fun KtorApplication.installJwtAuth(
    jwt: JwtConfiguration,
    sessionLiveness: SessionLiveness,
    socketTickets: SocketTicketStore? = null,
) {
    install(Authentication) {
        bearerJwt(jwt, sessionLiveness, socketTickets)
    }
}

private fun AuthenticationConfig.bearerJwt(
    jwt: JwtConfiguration,
    sessionLiveness: SessionLiveness,
    socketTickets: SocketTicketStore?,
) {
    bearer(JWT_PROVIDER) {
        // Header first, so every native client is byte-for-byte unchanged and the browser's
        // URL-borne ticket is only ever consulted when there is no header to prefer.
        authHeader { call ->
            call.request.parseAuthorizationHeader()
                ?: call.socketTicketFromQuery()
                ?: call.accessTokenFromCookie()
        }
        authenticate { credential ->
            // `authHeader` cannot suspend, so redemption happens here, where it can. Which carrier
            // produced this credential is decided by the same precedence `authHeader` applied: a
            // present header always wins, then the query ticket, then the cookie. Only the ticket
            // needs redeeming — the header and the cookie both carry the access token directly.
            val accessToken =
                when {
                    request.parseAuthorizationHeader() != null -> {
                        credential.token
                    }

                    request.queryParameters[SOCKET_TICKET_QUERY_PARAM] != null -> {
                        socketTickets?.redeem(credential.token) ?: run {
                            logJwtRejection("socket ticket was unknown, expired, or already spent")
                            return@authenticate null
                        }
                    }

                    else -> {
                        credential.token
                    }
                }
            val claims =
                try {
                    jwt.verify(accessToken)
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
 * The `ticket` query parameter shaped as the bearer credential the provider expects, or null when
 * there is none — or when what is there could never have been one.
 *
 * A blob outside RFC 7235's token68 alphabet makes [HttpAuthHeader.Single] throw, and an exception
 * escaping here would answer a bad credential with a 500. Absent and malformed both mean the same
 * thing to a caller — not authenticated. Tickets are base64url, so a real one always passes.
 */
private fun ApplicationCall.socketTicketFromQuery(): HttpAuthHeader? {
    val ticket = request.queryParameters[SOCKET_TICKET_QUERY_PARAM] ?: return null
    return try {
        HttpAuthHeader.Single(AuthScheme.Bearer, ticket)
    } catch (_: ParseException) {
        logJwtRejection("query ticket is not a well-formed credential")
        null
    }
}

/** The access token carried by [ACCESS_TOKEN_COOKIE], or null when the cookie is absent. */
private fun ApplicationCall.accessTokenFromCookie(): HttpAuthHeader? {
    val token = request.cookies[ACCESS_TOKEN_COOKIE] ?: return null
    return try {
        HttpAuthHeader.Single(AuthScheme.Bearer, token)
    } catch (_: ParseException) {
        logJwtRejection("cookie credential is not well-formed")
        null
    }
}

/**
 * Convenience accessor — returns the authenticated principal, or null if the
 * route wasn't gated by [JWT_PROVIDER]. Route handlers prefer this over
 * `call.principal<UserPrincipal>()` so the type is unambiguous at the call site.
 */
fun ApplicationCall.userPrincipalOrNull(): UserPrincipal? = principal<UserPrincipal>()
