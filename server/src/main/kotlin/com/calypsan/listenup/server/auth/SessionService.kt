@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.db.SessionEntity
import com.calypsan.listenup.server.db.SessionTable
import com.calypsan.listenup.server.db.UserEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
 */
class SessionService(
    private val db: Database,
    private val tokenHasher: RefreshTokenHasher,
    private val tokenGenerator: RefreshTokenGenerator,
    private val refreshTtl: Duration = DEFAULT_REFRESH_TTL,
    private val clock: Clock = Clock.System,
) {
    suspend fun createSession(
        userId: UserId,
        label: String? = null,
        userAgent: String? = null,
    ): IssuedSession {
        val raw = tokenGenerator.generate()
        val hash = tokenHasher.hash(raw)
        val now = clock.now().toEpochMilliseconds()
        val expires = (clock.now() + refreshTtl).toEpochMilliseconds()
        val sid = newSessionId()
        val familyId = newFamilyId()
        suspendTransaction(db) {
            SessionEntity.new(sid) {
                user = UserEntity[userId.value]
                refreshTokenHash = hash
                this.familyId = familyId
                previousHash = null
                this.label = label
                this.userAgent = userAgent
                createdAt = now
                expiresAt = expires
                lastUsedAt = now
                revokedAt = null
            }
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
            // idx_sessions_token_hash on refresh_token_hash.
            val live =
                SessionEntity
                    .find {
                        (SessionTable.refreshTokenHash eq incomingHash) and
                            (SessionTable.revokedAt eq null) and
                            (SessionTable.expiresAt greater clock.now().toEpochMilliseconds())
                    }.firstOrNull()
            if (live != null) {
                live.previousHash = live.refreshTokenHash
                live.refreshTokenHash = newHash
                live.lastUsedAt = now
                live.expiresAt = newExpires
                return@suspendTransaction RotatedSession(
                    sessionId = SessionId(live.id.value),
                    userId = UserId(live.user.id.value),
                    refreshToken = RefreshToken(newRaw),
                    expiresAt = newExpires,
                )
            }

            // Pass 2: previous-hash match → replay; revoke the family.
            val replay =
                SessionEntity
                    .find { SessionTable.previousHash eq incomingHash }
                    .firstOrNull()
            if (replay != null) {
                SessionTable.update({ SessionTable.familyId eq replay.familyId }) {
                    it[revokedAt] = now
                }
            }
            null
        }
    }

    suspend fun revoke(
        sessionId: SessionId,
        ownerUserId: UserId,
    ) {
        suspendTransaction(db) {
            SessionTable.update({
                (SessionTable.id eq sessionId.value) and
                    (SessionTable.userId eq ownerUserId.value) and
                    (SessionTable.revokedAt eq null)
            }) {
                it[revokedAt] = clock.now().toEpochMilliseconds()
            }
        }
    }

    suspend fun revokeAll(userId: UserId) {
        suspendTransaction(db) {
            SessionTable.update({
                (SessionTable.userId eq userId.value) and (SessionTable.revokedAt eq null)
            }) {
                it[revokedAt] = clock.now().toEpochMilliseconds()
            }
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
            SessionEntity
                .find { SessionTable.previousHash eq hash }
                .any()
        }
    }

    suspend fun isLive(sessionId: SessionId): Boolean =
        suspendTransaction(db) {
            val s = SessionEntity.findById(sessionId.value) ?: return@suspendTransaction false
            s.revokedAt == null && s.expiresAt > clock.now().toEpochMilliseconds()
        }

    suspend fun listActiveFor(userId: UserId): List<SessionEntity> =
        suspendTransaction(db) {
            SessionEntity
                .find {
                    (SessionTable.userId eq userId.value) and
                        (SessionTable.revokedAt eq null) and
                        (SessionTable.expiresAt greater clock.now().toEpochMilliseconds())
                }.sortedByDescending { it.lastUsedAt }
                .toList()
        }

    private fun newSessionId(): String = UUID.randomUUID().toString()

    private fun newFamilyId(): String = UUID.randomUUID().toString()

    companion object {
        private val DEFAULT_REFRESH_TTL: Duration = 30.days
    }
}
