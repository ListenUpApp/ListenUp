@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Sessions
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** Result of creating a new session — the raw refresh token is only returned here. */
data class IssuedSession(
    val sessionId: SessionId,
    val refreshToken: RefreshToken,
    val expiresAt: Long,
)

/** Result of rotating a refresh token. New token + same session id. */
data class RotatedSession(
    val sessionId: SessionId,
    val userId: UserId,
    val refreshToken: RefreshToken,
    val expiresAt: Long,
)

/**
 * Owns session lifecycle and the refresh-token rotation/family-revoke logic.
 *
 * Refresh tokens are hashed via HMAC-SHA-256 (keyed with a server pepper) before
 * storage. Deterministic hashing means rotation is an O(1) indexed lookup against
 * `sessions.refresh_token_hash` rather than a scan-and-Argon2id-verify of every
 * active session row.
 *
 * Persists over SQLDelight's [ListenUpDatabase] (the `sessions` table is a plain, non-syncable
 * server-owned aggregate). Each method runs its statements inside a single
 * [suspendTransaction] so multi-step operations (read-then-rotate, replay-then-revoke-family)
 * are atomic — exactly as they were under the Exposed `suspendTransaction(db) { … }` blocks.
 */
class SessionService(
    private val db: ListenUpDatabase,
    private val tokenHasher: RefreshTokenHasher,
    private val tokenGenerator: RefreshTokenGenerator,
    private val refreshTtl: Duration = DEFAULT_REFRESH_TTL,
    /**
     * Lost-response grace window (C4). A refresh token whose rotation completed on the server but
     * whose response never reached the client leaves the client holding the pre-rotation token. When
     * the client retries, the server sees that token in `previous_hash` and would normally read it as
     * a stolen-token replay and revoke the whole family — a dropped packet forcing a logout. Within
     * this window of the last rotation we instead treat the retry as benign: rotate again on the same
     * family and hand back a usable token. Genuine reuse after the window still hard-revokes.
     */
    private val reuseGracePeriod: Duration = DEFAULT_REUSE_GRACE,
    private val clock: Clock = Clock.System,
) {
    suspend fun createSession(
        userId: UserId,
        label: String? = null,
        deviceInfo: DeviceInfo? = null,
        userAgent: String? = null,
    ): IssuedSession {
        val raw = tokenGenerator.generate()
        val hash = tokenHasher.hash(raw)
        val now = clock.now().toEpochMilliseconds()
        val expires = (clock.now() + refreshTtl).toEpochMilliseconds()
        val sid = newSessionId()
        val familyId = newFamilyId()
        suspendTransaction(db) {
            db.sessionsQueries.insert(
                id = sid,
                user_id = userId.value,
                refresh_token_hash = hash,
                family_id = familyId,
                previous_hash = null,
                label = label,
                device_type = deviceInfo?.deviceType,
                platform = deviceInfo?.platform,
                platform_version = deviceInfo?.platformVersion,
                client_name = deviceInfo?.clientName,
                client_version = deviceInfo?.clientVersion,
                device_name = deviceInfo?.deviceName,
                device_model = deviceInfo?.deviceModel,
                user_agent = userAgent,
                created_at = now,
                expires_at = expires,
                last_used_at = now,
                revoked_at = null,
            )
        }
        return IssuedSession(SessionId(sid), RefreshToken(raw), expires)
    }

    /**
     * Returns null if the token is unrecognized or matches `previous_hash`
     * (replay → family revoked as a side effect).
     */
    suspend fun rotate(token: RefreshToken): RotatedSession? {
        val incomingHash = tokenHasher.hash(token.value)
        val now = clock.now().toEpochMilliseconds()
        val newRaw = tokenGenerator.generate()
        val newHash = tokenHasher.hash(newRaw)
        val newExpires = (clock.now() + refreshTtl).toEpochMilliseconds()

        return suspendTransaction(db) {
            // Pass 1: live-token match → rotate. Indexed via UNIQUE INDEX
            // idx_sessions_token_hash on refresh_token_hash. The query bounds revoked/expired,
            // so a revoked or expired session never rotates.
            val live =
                db.sessionsQueries
                    .selectLiveByTokenHash(refresh_token_hash = incomingHash, now = now)
                    .executeAsOneOrNull()
            if (live != null) {
                db.sessionsQueries.rotate(
                    previous_hash = live.refresh_token_hash,
                    refresh_token_hash = newHash,
                    last_used_at = now,
                    expires_at = newExpires,
                    id = live.id,
                )
                return@suspendTransaction RotatedSession(
                    sessionId = SessionId(live.id),
                    userId = UserId(live.user_id),
                    refreshToken = RefreshToken(newRaw),
                    expiresAt = newExpires,
                )
            }

            // Pass 2: previous-hash match. This is either a lost-response retry (benign) or a
            // stolen-token replay (attack). The reuse-grace window (C4) distinguishes them: a live
            // session whose last rotation was within the window is a dropped-response retry — rotate
            // again on the same family without revoking. Anything else is a genuine replay → revoke.
            val replay =
                db.sessionsQueries
                    .selectByPreviousHash(previous_hash = incomingHash)
                    .executeAsOneOrNull()
                    ?: return@suspendTransaction null // unknown token — nothing to rotate or revoke.

            val withinGrace =
                replay.revoked_at == null &&
                    replay.expires_at > now &&
                    now - replay.last_used_at <= reuseGracePeriod.inWholeMilliseconds
            if (withinGrace) {
                // Re-rotate on the same lineage. previous_hash stays keyed on the incoming token so
                // the window remains anchored to it; a late replay of the same token past the window
                // still lands in the revoke branch below.
                db.sessionsQueries.rotate(
                    previous_hash = incomingHash,
                    refresh_token_hash = newHash,
                    last_used_at = now,
                    expires_at = newExpires,
                    id = replay.id,
                )
                return@suspendTransaction RotatedSession(
                    sessionId = SessionId(replay.id),
                    userId = UserId(replay.user_id),
                    refreshToken = RefreshToken(newRaw),
                    expiresAt = newExpires,
                )
            }

            db.sessionsQueries.revokeFamily(revoked_at = now, family_id = replay.family_id)
            null
        }
    }

    suspend fun revoke(
        sessionId: SessionId,
        ownerUserId: UserId,
    ) {
        suspendTransaction(db) {
            db.sessionsQueries.revokeByIdForUser(
                revoked_at = clock.now().toEpochMilliseconds(),
                id = sessionId.value,
                user_id = ownerUserId.value,
            )
        }
    }

    suspend fun revokeAll(userId: UserId) {
        suspendTransaction(db) {
            db.sessionsQueries.revokeAllForUser(
                revoked_at = clock.now().toEpochMilliseconds(),
                user_id = userId.value,
            )
        }
    }

    /**
     * True iff [token] matches a session's `previous_hash` — i.e. an attempt
     * to reuse a token that has already been rotated. [rotate] returns null
     * for both "unknown" and "replayed-and-family-revoked"; this lets the
     * caller distinguish so the wire-level `InvalidRefreshToken.familyRevoked`
     * carries truthful information.
     *
     * Cheap (one indexed read against `idx_sessions_previous_hash`) and only
     * runs on the error path.
     */
    suspend fun wasReplay(token: RefreshToken): Boolean {
        val hash = tokenHasher.hash(token.value)
        return suspendTransaction(db) {
            db.sessionsQueries
                .selectByPreviousHash(previous_hash = hash)
                .executeAsOneOrNull() != null
        }
    }

    suspend fun isLive(sessionId: SessionId): Boolean =
        suspendTransaction(db) {
            val s =
                db.sessionsQueries
                    .selectById(id = sessionId.value)
                    .executeAsOneOrNull()
                    ?: return@suspendTransaction false
            s.revoked_at == null && s.expires_at > clock.now().toEpochMilliseconds()
        }

    suspend fun listActiveFor(userId: UserId): List<Sessions> =
        suspendTransaction(db) {
            db.sessionsQueries
                .selectActiveForUser(user_id = userId.value, now = clock.now().toEpochMilliseconds())
                .executeAsList()
        }

    /**
     * Hard-deletes all session rows whose `expires_at` timestamp is strictly less than [beforeMs]
     * (epoch milliseconds). Returns the count of deleted rows. Called by
     * [com.calypsan.listenup.server.scheduler.ExpiredSessionCleanupTask] on a periodic schedule.
     *
     * Sweeps `push_tokens` orphaned by the delete in the same transaction — a token's liveness
     * already comes from its session JOIN (see PushTokens.sq), so this is physical cleanup, not
     * a correctness dependency.
     */
    suspend fun deleteExpired(beforeMs: Long): Int =
        suspendTransaction(db) {
            db.sessionsQueries.deleteExpired(before_ms = beforeMs)
            val removed =
                db.sessionsQueries
                    .changes()
                    .executeAsOne()
                    .toInt()
            db.pushTokensQueries.deleteOrphaned()
            removed
        }

    private fun newSessionId(): String = Uuid.random().toString()

    private fun newFamilyId(): String = Uuid.random().toString()

    companion object {
        private val DEFAULT_REFRESH_TTL: Duration = 30.days
        private val DEFAULT_REUSE_GRACE: Duration = 60.seconds
    }
}
