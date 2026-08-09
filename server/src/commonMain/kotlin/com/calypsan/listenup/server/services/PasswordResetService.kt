@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.PasswordResetDecisionOutcome
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Email
import com.calypsan.listenup.server.auth.PepperedHasher
import com.calypsan.listenup.server.auth.ResetCodeGenerator
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Owns the password-reset lifecycle: a member opens a request, an admin decides it, and the
 * requesting device completes it with both its claim and the code the admin conveyed.
 *
 * The account is never taken out of service — a pending reset leaves the user ACTIVE and
 * signed in wherever they already are. Only completion revokes anything.
 */
class PasswordResetService(
    private val db: ListenUpDatabase,
    private val hasher: PepperedHasher,
    private val codes: ResetCodeGenerator,
    private val clock: Clock,
) {
    /**
     * Opens a reset request for the account registered to [email].
     *
     * **Returns Success whether or not that account exists.** For an unknown address the
     * ticket is minted and discarded — never persisted — so the response is indistinguishable
     * from the known case. This endpoint is unauthenticated and must not become an
     * account-existence oracle, which is why there is no failure branch here at all.
     *
     * Any live request for the account is superseded, so a user who taps twice does not leave
     * a queue of stale rows behind them.
     *
     * The *shape* of the response is identical on both paths; the *cost* is not. The known
     * path runs an extra transaction and an HMAC, so a determined attacker who can sample many
     * probes could in principle distinguish them by timing. Accepted deliberately: closing it
     * means issuing a matching dummy write, which is more machinery than this threat model
     * justifies. Rate limiting at the route layer is what keeps the sampling attack impractical.
     */
    suspend fun request(
        email: String,
        deviceClaim: String,
    ): AppResult<PasswordResetTicket> {
        val now = clock.now().toEpochMilliseconds()
        val expiresAt = now + TTL.inWholeMilliseconds
        val ticketId = Uuid.random().toString()

        val normalized = Email.normalize(email)
        val user = db.usersQueries.selectByEmailNormalized(normalized).executeAsOneOrNull()
        if (user != null) {
            suspendTransaction(db) {
                db.passwordResetRequestsQueries.expireLiveForUser(user.id)
                db.passwordResetRequestsQueries.insert(
                    id = ticketId,
                    user_id = user.id,
                    requested_at = now,
                    expires_at = expiresAt,
                    status = "PENDING",
                    device_claim_hash = hasher.hash(CLAIM_DOMAIN + deviceClaim),
                )
            }
        }

        return AppResult.Success(PasswordResetTicket(ticketId = ticketId, expiresAt = expiresAt))
    }

    /**
     * Approve or deny a pending request.
     *
     * **On approval the plaintext code is returned here and nowhere else** — it is for the
     * admin to convey to the requester through a channel they already trust, and that act of
     * conveyance is the identity check this whole design rests on. It is never listed, never
     * pushed, and never logged; only its hash is stored.
     *
     * The returned code is the grouped display form (`ABCD-2345`) because an admin reads it
     * aloud; the hash is over the canonical form. [ResetCodeGenerator.normalize] bridges the
     * two when the requester types it back.
     */
    suspend fun decide(
        requestId: String,
        approved: Boolean,
        adminId: String,
    ): AppResult<PasswordResetDecisionOutcome> {
        val now = clock.now().toEpochMilliseconds()
        // Minted unconditionally so the transaction body below stays a pure read-then-write —
        // a code discarded because the guard fails is never persisted or returned, so the cost
        // of generating one we don't use is free.
        val code = if (approved) codes.generate() else null

        val decided =
            suspendTransaction(db) {
                // Re-read INSIDE the transaction, not before it. suspendTransaction retries this
                // whole closure on SQLITE_BUSY_SNAPSHOT, so a concurrent decide() that committed
                // between an outside-the-transaction read and this write would otherwise let both
                // callers see PENDING and both report Success — with one admin's code silently
                // becoming unusable. Reading here means the retry re-observes the row post-commit
                // and the second caller correctly falls through to "not found".
                val row = db.passwordResetRequestsQueries.selectById(requestId).executeAsOneOrNull()
                // Unknown, already-decided, and expired all collapse to the same shape. This reuses
                // AuthError.ResetRequestNotFound rather than minting admin-only variants — a simplicity
                // call, not a security one: decide() is admin-gated, so there is no attacker here to
                // withhold detail from. An admin who taps Approve on a stale row sees "not found" and
                // re-checks the queue.
                if (row == null || row.status != "PENDING" || row.expires_at <= now) {
                    false
                } else {
                    if (code != null) {
                        db.passwordResetRequestsQueries.markApproved(
                            code_hash = hasher.hash(CODE_DOMAIN + code),
                            decided_by = adminId,
                            decided_at = now,
                            id = requestId,
                        )
                    } else {
                        db.passwordResetRequestsQueries.markDenied(
                            decided_by = adminId,
                            decided_at = now,
                            id = requestId,
                        )
                    }
                    true
                }
            }

        if (!decided) return AppResult.Failure(AuthError.ResetRequestNotFound())
        return if (code != null) {
            AppResult.Success(PasswordResetDecisionOutcome.Approved(ResetCodeGenerator.format(code)))
        } else {
            AppResult.Success(PasswordResetDecisionOutcome.Denied)
        }
    }

    /** Pending requests for the admin queue, newest first. Carries no codes. */
    suspend fun listPending(): List<PasswordResetRequest> {
        val now = clock.now().toEpochMilliseconds()
        return db.passwordResetRequestsQueries.selectPending(now).executeAsList().map { row ->
            val user = db.usersQueries.selectById(row.user_id).executeAsOneOrNull()
            PasswordResetRequest(
                id = row.id,
                userId = UserId(row.user_id),
                displayName = user?.display_name.orEmpty(),
                email = user?.email.orEmpty(),
                requestedAt = row.requested_at,
                expiresAt = row.expires_at,
            )
        }
    }

    companion object {
        /** How long a request stays live. Short enough to bound exposure, long enough to phone someone. */
        internal val TTL = 15.minutes

        /** Wrong-code budget. Spending it kills the request. */
        internal const val MAX_ATTEMPTS = 5

        // Domain separation. The reset hasher is keyed with the SAME server pepper as the
        // refresh-token hasher — there is one pepper in resolveServerSecrets, and minting a
        // second is a config migration that buys little. Tagging the input instead is free and
        // makes a hash produced in one context structurally unusable in another.
        //
        // These tags must be applied at EVERY call site — write and compare alike. A tag used
        // when storing but not when verifying silently breaks the check while every happy-path
        // test still passes.
        internal const val CODE_DOMAIN = "listenup:reset-code:"
        internal const val CLAIM_DOMAIN = "listenup:reset-claim:"
    }
}
