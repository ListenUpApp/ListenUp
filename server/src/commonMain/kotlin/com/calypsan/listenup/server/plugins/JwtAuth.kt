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
 * Name of the provider gating the byte-serving GETs a DOM element fetches for itself — covers,
 * documents, contributor photos, series covers, avatars.
 *
 * It differs from [JWT_PROVIDER] in one respect: it also accepts [ACCESS_TOKEN_COOKIE]. That has to
 * be a separate provider rather than a third carrier on the existing one, because a cookie is the
 * one credential the browser attaches on its own, to requests this server did not initiate. A
 * WebSocket upgrade is not subject to CORS, so a cookie honoured by [JWT_PROVIDER] would let a
 * cross-site page open the entire authenticated RPC surface on a visitor's session. `SameSite`
 * would stop that — but `SameSite` is an attribute the server asks a client to enforce, and a
 * credential this broad should not rest on a promise made by the party being defended against.
 *
 * So the cookie's reach is bounded by the routes it is mounted on instead, and those routes are all
 * reads of bytes. Mount it as a **sibling** of the [JWT_PROVIDER] block, never nested inside it:
 * Ktor's route interceptors are inherited, so a nested block would stack both providers and the
 * outer one would reject a cookie-only request before this one ever ran.
 */
const val DOM_FETCH_PROVIDER = "jwt-dom-fetch"

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
 *
 * Read by [DOM_FETCH_PROVIDER] alone — see there for why that boundary is the security property.
 */
const val ACCESS_TOKEN_COOKIE = "listenup_access"

/**
 * Installs the two bearer providers. Both verify the access JWT and then run an `isLive` check on
 * the session row before handing back a [UserPrincipal] — a revoked or expired session can't sneak
 * through on a still-valid JWT, so the access window is bounded by the JWT's TTL (≤15m).
 *
 * They differ only in which carriers they will read a credential from:
 *  - [JWT_PROVIDER] — `Authorization` header, or a single-use socket ticket in the query. Gates the
 *    RPC surface and every mutating route.
 *  - [DOM_FETCH_PROVIDER] — `Authorization` header, or [ACCESS_TOKEN_COOKIE]. Gates the byte-serving
 *    GETs only.
 *
 * [socketTickets] is the browser's way onto the socket: a ticket in the query redeems to the access
 * token it stands for, which is then verified on exactly the path a header-borne token takes. Null
 * disables the ticket path entirely (no browser client, no query credential accepted).
 */
fun KtorApplication.installJwtAuth(
    jwt: JwtConfiguration,
    sessionLiveness: SessionLiveness,
    socketTickets: SocketTicketStore? = null,
) {
    install(Authentication) {
        bearerJwt(jwt, sessionLiveness, socketTickets)
        domFetchJwt(jwt, sessionLiveness)
    }
}

private fun AuthenticationConfig.domFetchJwt(
    jwt: JwtConfiguration,
    sessionLiveness: SessionLiveness,
) {
    bearer(DOM_FETCH_PROVIDER) {
        // Header first for the same reason as above: a native client's request is read exactly as
        // it always was. No ticket here — a ticket is spent by the connection it opens, and a page
        // full of covers issues one request per image.
        authHeader { call ->
            call.request.parseAuthorizationHeader() ?: call.accessTokenFromCookie()
        }
        // Both carriers hold the access token itself, so there is nothing to redeem and no
        // precedence to re-derive — whichever one `authHeader` chose, it verifies the same way.
        authenticate { credential -> principalFor(credential.token, jwt, sessionLiveness) }
    }
}

/**
 * The verification both providers share: a valid signature is necessary but not sufficient, because
 * a revoked session must not stay reachable for the rest of a still-valid JWT's TTL.
 */
private suspend fun principalFor(
    accessToken: String,
    jwt: JwtConfiguration,
    sessionLiveness: SessionLiveness,
): UserPrincipal? {
    val claims =
        try {
            jwt.verify(accessToken)
        } catch (_: JwtVerificationException) {
            logJwtRejection("token verification failed")
            return null
        }
    if (!sessionLiveness.isLive(claims.sessionId)) {
        logJwtRejection("session no longer live for sessionId=${claims.sessionId}")
        return null
    }
    return UserPrincipal(claims.userId, claims.sessionId, claims.role)
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
            call.request.parseAuthorizationHeader() ?: call.socketTicketFromQuery()
        }
        authenticate { credential ->
            // `authHeader` cannot suspend, so redemption happens here, where it can. Which of the
            // two carriers produced this credential is decided by the same rule `authHeader`
            // applied: a present header always wins, so a ticket is only in play without one.
            val accessToken =
                if (request.parseAuthorizationHeader() != null) {
                    credential.token
                } else {
                    socketTickets?.redeem(credential.token) ?: run {
                        logJwtRejection("socket ticket was unknown, expired, or already spent")
                        return@authenticate null
                    }
                }
            principalFor(accessToken, jwt, sessionLiveness)
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
