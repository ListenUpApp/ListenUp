package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import io.ktor.http.encodeURLParameter
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whether this platform's WebSocket upgrade can carry request headers.
 *
 * `false` on the browser and true everywhere else, and that is not a preference — the DOM
 * `WebSocket` constructor takes `(url, protocols)` and nothing else, so Ktor's JS engine has
 * nowhere to put the `Authorization` header the `Auth` plugin writes and silently drops it. Every
 * other engine (OkHttp, Darwin, CIO, Node's `ws`) upgrades over a real HTTP request and sends it.
 *
 * Expressed as a platform fact rather than a runtime feature-probe because it *is* one: no browser
 * can do this, and no amount of configuration changes that.
 */
internal expect val wsUpgradeCarriesHeaders: Boolean

/**
 * Query parameter carrying the single-use socket ticket when the upgrade cannot carry a header.
 *
 * Mirrored server-side by `SOCKET_TICKET_QUERY_PARAM` in
 * `server/.../server/plugins/JwtAuth.kt` — the two must agree.
 */
private const val SOCKET_TICKET_QUERY_PARAM = "ticket"

/**
 * The URL an RPC channel opens its WebSocket against: the mount, plus — only where the upgrade
 * cannot carry a header, and only for the bearer-gated mount — a single-use ticket.
 *
 * **A ticket, never the access token.** A URL is the one carrier a browser has left, and it is also
 * the one carrier that gets written down: a self-hosted instance usually sits behind a reverse
 * proxy, and nginx's `combined` format logs `$request` — query string included — as does Caddy's
 * `uri`. Putting a 15-minute JWT there would mean writing a live credential into a log file on
 * every reconnect, turning read-only log access into account access. A ticket is spent by the
 * connection it opens and expires seconds later, so the same log line is worth nothing.
 *
 * The token itself is not homeless: it travels as an argument to
 * [AuthServicePublic.issueSocketTicket] inside the public mount's WebSocket *payload*, which no
 * proxy logs.
 *
 * [upgradeCarriesHeaders] defaults to the platform fact and is a parameter only so both branches
 * are reachable from a test on any platform.
 */
internal suspend fun rpcMountUrl(
    baseUrl: String,
    policy: RpcPolicy,
    upgradeCarriesHeaders: Boolean = wsUpgradeCarriesHeaders,
    socketTicket: suspend () -> String?,
): String {
    val mountUrl = "$baseUrl${policy.mount}"

    // The public mount is pre-auth by definition — a credential there would be pointless at best
    // and a leaked one on an anonymous handshake at worst.
    if (upgradeCarriesHeaders || policy.recovery != RecoveryMode.Authed) return mountUrl

    val ticket = socketTicket() ?: return mountUrl
    return "$mountUrl?$SOCKET_TICKET_QUERY_PARAM=${ticket.encodeURLParameter()}"
}

/**
 * Latency budget for [mintSocketTicket] — see its KDoc. The mint runs INSIDE [RpcProxyCache.lease],
 * before that call's own `withTimeout` starts, so it sits outside every caller's budget unless
 * bounded here explicitly — on every web reconnect, not just a rare recovery path. A hung mint
 * folds to a transport failure (not an [AuthError]), so it degrades to a ticket-less URL without
 * burning a refresh, and the next reconnect attempt simply mints again — no reason to wait
 * anywhere near the 15s RPC default for that.
 */
private val TICKET_MINT_TIMEOUT = 800.milliseconds

/**
 * Trades this client's access token for a one-connection ticket, or null when there is no session
 * or the mint cannot be made good.
 *
 * **The mint's typed failure is the one auth signal a browser can see.** This function used to
 * return null on any failure, on the theory that a ticket-less upgrade 401s into the existing
 * [RpcAuthRecovery] heal — but a DOM `WebSocket` error carries no status code, so on the only
 * platform that mints tickets that 401 was invisible, the heal never fired, and an expired access
 * token wedged the client into a reconnect loop the server kept refusing. So the heal runs HERE:
 * an [AuthError] from the mint is the server saying "that access token is dead", and [recoverAuth]
 * (the same single-flight [RpcAuthRecovery] the header-carrying platforms use) refreshes it and
 * the mint retries once with the fresh token.
 *
 * Null still falls back to a ticket-less upgrade: for a transient refresh failure the next
 * reconnect attempt tries again, and for a server-confirmed dead session [RpcAuthRecovery] has
 * already lapsed the state — a successful later refresh flips it back
 * (`AuthSessionStore.saveAuthTokens`), which is what makes the lapse recoverable rather than
 * sticky.
 */
internal suspend fun mintSocketTicket(
    accessToken: suspend () -> String?,
    authChannel: () -> RpcChannel<AuthServicePublic>,
    recoverAuth: suspend () -> AuthRecoveryOutcome,
): String? {
    val token = accessToken() ?: return null
    // Not idempotent: every call deliberately mints a NEW ticket, so a re-fire is never a no-op.
    val first = authChannel().call(timeout = TICKET_MINT_TIMEOUT, idempotent = false) { it.issueSocketTicket(token) }
    when (first) {
        is AppResult.Success -> {
            return first.data.value
        }

        is AppResult.Failure -> {
            // Only an auth-shaped refusal implicates the token. Burning a rotation on a network
            // blip would invalidate a sibling tab's refresh token for nothing.
            if (first.error !is AuthError) return null
        }
    }
    if (recoverAuth() != AuthRecoveryOutcome.Refreshed) return null
    val fresh = accessToken() ?: return null
    val retried =
        authChannel().call(timeout = TICKET_MINT_TIMEOUT, idempotent = false) { it.issueSocketTicket(fresh) }
    return (retried as? AppResult.Success)?.data?.value
}
