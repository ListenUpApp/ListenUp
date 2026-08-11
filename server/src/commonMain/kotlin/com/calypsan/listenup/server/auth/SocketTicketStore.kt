@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Single-use, seconds-lived handles that stand in for an access token on a WebSocket upgrade.
 *
 * **Why this exists at all:** the DOM `WebSocket` constructor is `(url, protocols)` and has no
 * header parameter, so a browser physically cannot send `Authorization` on the upgrade. The only
 * place left to put a credential is the URL — and a URL is the one place a credential must not go,
 * because a self-hosted deployment almost always has a reverse proxy in front of it and nginx's
 * `combined` format logs `$request` (query string included), as does Caddy's `uri`. A 15-minute JWT
 * written to an access log on every reconnect turns read-only log access into account access.
 *
 * A ticket closes that: what lands in the log is an opaque handle that is spent the moment it is
 * used and worthless [ttl] later. The JWT itself never leaves the WebSocket payload it was minted
 * over.
 *
 * **A ticket is an envelope, not a credential.** [redeem] hands back the original access token, and
 * the bearer provider then verifies it exactly as it verifies a header-borne one — same signature
 * check, same expiry, same session-liveness probe. Nothing about what a session *is* changes here,
 * which is what keeps this cheap to trust.
 *
 * In-memory and per-process, the same call the rest of the auth surface makes (see
 * [LoginRateLimiter]): a self-hosted instance is one node, and a ticket that does not survive a
 * restart merely costs a reconnect.
 */
class SocketTicketStore(
    private val clock: Clock,
    private val ttl: Duration = DEFAULT_TTL,
) {
    private class Entry(
        val accessToken: String,
        val expiresAtMillis: Long,
    )

    private val mutex = Mutex()
    private val tickets = mutableMapOf<String, Entry>()

    /** Mint a ticket standing in for [accessToken]. The caller has already proved the token is valid. */
    suspend fun issue(accessToken: String): String =
        mutex.withLock {
            val now = clock.now().toEpochMilliseconds()
            sweep(now)
            val ticket = URL_NO_PAD.encode(CryptographyRandom.nextBytes(TICKET_BYTES))
            tickets[ticket] = Entry(accessToken, now + ttl.inWholeMilliseconds)
            ticket
        }

    /**
     * Spend [ticket], returning the access token it stood for — or null when it is unknown, already
     * spent, or expired. Removal happens before the expiry check so a redeem attempt always
     * consumes, whatever the outcome.
     */
    suspend fun redeem(ticket: String): String? =
        mutex.withLock {
            val now = clock.now().toEpochMilliseconds()
            sweep(now)
            tickets.remove(ticket)?.takeIf { it.expiresAtMillis > now }?.accessToken
        }

    /** Live (unexpired, unspent) ticket count — for tests that pin the sweep. */
    internal suspend fun liveTicketCount(): Int =
        mutex.withLock {
            sweep(clock.now().toEpochMilliseconds())
            tickets.size
        }

    /** Drops what the clock has already invalidated, so an unredeemed burst cannot accumulate. */
    private fun sweep(nowMillis: Long) {
        tickets.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
    }

    companion object {
        /**
         * Long enough that a mint-then-connect round trip never races it — the two happen back to
         * back — and short enough that a ticket in a log file is history. Single-use is the primary
         * guarantee; this is the backstop for a ticket that is minted and then never used.
         */
        val DEFAULT_TTL: Duration = 30.seconds

        private const val TICKET_BYTES = 32
        private val URL_NO_PAD = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}
