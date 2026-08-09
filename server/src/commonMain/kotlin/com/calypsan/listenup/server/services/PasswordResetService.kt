@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.PasswordResetDecisionOutcome
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.api.dto.auth.PasswordResetStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.Email
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PasswordPolicy
import com.calypsan.listenup.server.auth.PepperedHasher
import com.calypsan.listenup.server.auth.ResetCodeGenerator
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The one session operation a completed reset performs. Narrowed to a single method so it is
 * non-null and impossible to forget in DI wiring — a reset that silently skipped revocation
 * would leave an intruder signed in, which is the exact failure this step exists to prevent.
 */
fun interface SessionRevoker {
    suspend fun revokeAll(userId: UserId)
}

/**
 * Owns the password-reset lifecycle: a member opens a request, an admin decides it, and the
 * requesting device completes it with both its claim and the code the admin conveyed.
 *
 * The account is never taken out of service — a pending reset leaves the user ACTIVE and
 * signed in wherever they already are. Only completion revokes anything.
 *
 * [sessions] is a narrowed [SessionRevoker], not the full session service — non-null with no
 * default, so a construction site that forgets to wire it is a compile error, not a silent
 * skip of the mandatory post-reset revocation (see the KDoc on [complete] for why that
 * matters). [passwords] defaults to a working [Argon2Limiter], since — unlike session
 * revocation — there is no legitimate "skip it" state for hashing the new password.
 */
class PasswordResetService(
    private val db: ListenUpDatabase,
    private val hasher: PepperedHasher,
    private val codes: ResetCodeGenerator,
    private val clock: Clock,
    private val sessions: SessionRevoker,
    private val passwords: Argon2Limiter = Argon2Limiter(PasswordHasher()),
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
        val rowUuid = Uuid.random().toString()
        val ticketId = mintTicketId(rowUuid, now)

        val normalized = Email.normalize(email)
        val user = db.usersQueries.selectByEmailNormalized(normalized).executeAsOneOrNull()
        if (user != null) {
            suspendTransaction(db) {
                db.passwordResetRequestsQueries.expireLiveForUser(user.id)
                db.passwordResetRequestsQueries.insert(
                    id = rowUuid,
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
        // requestId is the same signed composite request() returns (listPending() reconstructs
        // it identically from the persisted row) — parse it back to the row's real primary key.
        // A parse failure is indistinguishable from "not found" below: decide() is admin-gated,
        // so there is no oracle to protect here, just an ordinary bad-input case.
        val rowUuid = parseTicketId(requestId)?.rowUuid

        val decided =
            rowUuid?.let { id ->
                suspendTransaction(db) {
                    // Re-read INSIDE the transaction, not before it. suspendTransaction retries this
                    // whole closure on SQLITE_BUSY_SNAPSHOT, so a concurrent decide() that committed
                    // between an outside-the-transaction read and this write would otherwise let both
                    // callers see PENDING and both report Success — with one admin's code silently
                    // becoming unusable. Reading here means the retry re-observes the row post-commit
                    // and the second caller correctly falls through to "not found".
                    val row = db.passwordResetRequestsQueries.selectById(id).executeAsOneOrNull()
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
                                id = id,
                            )
                        } else {
                            db.passwordResetRequestsQueries.markDenied(
                                decided_by = adminId,
                                decided_at = now,
                                id = id,
                            )
                        }
                        true
                    }
                }
            } ?: false

        if (!decided) return AppResult.Failure(AuthError.ResetRequestNotFound())
        return if (code != null) {
            AppResult.Success(PasswordResetDecisionOutcome.Approved(ResetCodeGenerator.format(code)))
        } else {
            AppResult.Success(PasswordResetDecisionOutcome.Denied)
        }
    }

    /**
     * Completes an approved reset. Requires the device claim minted at request time **and**
     * the code the admin conveyed out of band — either alone is insufficient, which is the
     * point: the code proves a human vouched for the requester, the claim proves the reply
     * came back to the device that asked.
     *
     * On success **every session for the account is revoked**. If the reset followed a
     * compromise, leaving the intruder signed in would defeat the entire exercise.
     *
     * The whole read → verify → increment-or-consume sequence runs inside one transaction.
     * That is not incidental: a non-atomic attempt counter would let an attacker fire
     * concurrent guesses and get far more than [MAX_ATTEMPTS] tries, which is the only thing
     * standing between a 40-bit code and brute force.
     */
    suspend fun complete(
        ticketId: String,
        claimSecret: String,
        code: String,
        newPassword: String,
    ): AppResult<Unit> {
        // Validate the password BEFORE touching the request — a weak new password is the
        // caller's mistake, not a failed authentication, and must not burn an attempt.
        PasswordPolicy.validate(newPassword).let { if (it is AppResult.Failure) return it }

        val now = clock.now().toEpochMilliseconds()
        // Hashed unconditionally, before we know whether the claim/code will check out — Argon2
        // is CPU-bound and must not run inside suspendTransaction's non-suspend body (mirrors
        // ProfileServiceImpl.changePassword's "hash outside the transaction" shape). Bounded by
        // Argon2Limiter, so a burst of guesses against one ticket cannot stampede memory/CPU.
        val newHash = passwords.hash(newPassword)
        // Same signed composite [request] returns; recover the row's real primary key. A parse
        // failure reads as NotFound below — identical to ResetRequestNotFound's existing
        // contract for an unknown ticket, so this adds no new shape for an attacker to read.
        val rowUuid = parseTicketId(ticketId)?.rowUuid

        val outcome: CompletionOutcome =
            if (rowUuid == null) {
                CompletionOutcome.NotFound
            } else {
                suspendTransaction(db) {
                    val row =
                        db.passwordResetRequestsQueries.selectById(rowUuid).executeAsOneOrNull()
                            ?: return@suspendTransaction CompletionOutcome.NotFound

                    // Expiry checked here, not by a sweep — a missed purge must never resurrect a
                    // request. CONSUMED/EXPIRED collapse into the same "not found" shape as unknown
                    // (ResetRequestNotFound's contract); DENIED is deliberately NOT here — it must
                    // fall through to the status-check below so it reports ResetNotApproved, per
                    // that error's own contract ("still pending, or after it was denied — both
                    // collapse to one shape").
                    if (row.expires_at <= now || row.status in TERMINAL_STATES) {
                        return@suspendTransaction CompletionOutcome.NotFound
                    }
                    if (row.attempts >= MAX_ATTEMPTS) {
                        return@suspendTransaction CompletionOutcome.Exhausted
                    }
                    if (row.status != "APPROVED") {
                        return@suspendTransaction CompletionOutcome.NotApproved
                    }

                    val claimOk = hasher.hash(CLAIM_DOMAIN + claimSecret) == row.device_claim_hash
                    val codeOk =
                        hasher.hash(CODE_DOMAIN + ResetCodeGenerator.normalize(code)) == row.code_hash

                    if (!claimOk || !codeOk) {
                        db.passwordResetRequestsQueries.incrementAttempts(rowUuid)
                        val remaining = (MAX_ATTEMPTS - (row.attempts + 1).toInt()).coerceAtLeast(0)
                        return@suspendTransaction CompletionOutcome.Wrong(remaining)
                    }

                    db.usersQueries.updatePasswordHashAt(password_hash = newHash, updated_at = now, id = row.user_id)
                    db.passwordResetRequestsQueries.markConsumed(rowUuid)
                    CompletionOutcome.Consumed(row.user_id)
                }
            }

        return when (outcome) {
            CompletionOutcome.NotFound -> {
                AppResult.Failure(AuthError.ResetRequestNotFound())
            }

            CompletionOutcome.Exhausted -> {
                AppResult.Failure(AuthError.ResetAttemptsExhausted())
            }

            CompletionOutcome.NotApproved -> {
                AppResult.Failure(AuthError.ResetNotApproved())
            }

            is CompletionOutcome.Wrong -> {
                AppResult.Failure(AuthError.ResetCodeIncorrect(attemptsRemaining = outcome.remaining))
            }

            is CompletionOutcome.Consumed -> {
                // Outside the transaction: session revocation is a different aggregate, and
                // holding the write lock across it would serialise unrelated work.
                sessions.revokeAll(UserId(outcome.userId))
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Pending requests for the admin queue, newest first. Carries no codes.
     *
     * [PasswordResetRequest.id] is the same signed composite [request] returns to the ticket
     * holder — reconstructed deterministically from the row's own `id`/`requested_at`, so it
     * round-trips through [decide] to the same row. There is no separate "admin id" identifier
     * space to keep track of.
     */
    suspend fun listPending(): List<PasswordResetRequest> {
        val now = clock.now().toEpochMilliseconds()
        return db.passwordResetRequestsQueries.selectPending(now).executeAsList().map { row ->
            val user = db.usersQueries.selectById(row.user_id).executeAsOneOrNull()
            PasswordResetRequest(
                id = mintTicketId(row.id, row.requested_at),
                userId = UserId(row.user_id),
                displayName = user?.display_name.orEmpty(),
                email = user?.email.orEmpty(),
                requestedAt = row.requested_at,
                expiresAt = row.expires_at,
            )
        }
    }

    /**
     * Streams [ticketId]'s status: current state immediately, then live updates, completing the
     * moment the state turns terminal (`DENIED`/`CONSUMED`/`EXPIRED` — `PENDING` and `APPROVED`
     * are not). Backed by a poll rather than a push, mirroring
     * [com.calypsan.listenup.server.auth.RegistrationStatusWatch]'s never-stranded shape.
     *
     * ⛔ **A [ticketId] with no backing row does NOT error and does NOT read as terminal.** That
     * would make [request] an account-existence oracle one call later — see the KDoc on
     * [com.calypsan.listenup.api.AuthServicePublic.observePasswordResetStatus]. Instead it behaves
     * exactly like a real request nobody has approved yet: a phantom expiry is computed from the
     * ticket's own **signed issue time** (see [mintTicketId]) — not re-minted at subscribe time,
     * which would let a delayed subscribe reveal whether the address was real by comparing
     * against the `expiresAt` the caller already holds from [request]'s response — and the
     * stream reports `PENDING` until that phantom expiry passes, then `EXPIRED`.
     */
    fun observeStatus(ticketId: String): Flow<PasswordResetStatusEvent> =
        flow {
            // A ticket that fails to parse/verify is never distinguished from a legitimate
            // phantom — see parseTicketId's KDoc. Its issue time is unknowable, so `now` is the
            // only honest choice; no genuine client ever produces one, so this path is never hit
            // by a real "does the address exist" probe.
            val parsed = parseTicketId(ticketId)
            val issuedAt = parsed?.issuedAt ?: clock.now().toEpochMilliseconds()
            val phantomExpiresAt = issuedAt + TTL.inWholeMilliseconds
            emitAll(statusPoll(parsed?.rowUuid, phantomExpiresAt))
        }.transformWhile { event ->
            emit(event)
            event.status == PasswordResetStatus.PENDING || event.status == PasswordResetStatus.APPROVED
        }

    /** Re-reads [currentStatus] on a fixed cadence forever — the caller decides when to stop collecting. */
    private fun statusPoll(
        rowUuid: String?,
        phantomExpiresAt: Long,
    ): Flow<PasswordResetStatusEvent> =
        flow {
            while (true) {
                emit(currentStatus(rowUuid, phantomExpiresAt))
                delay(STATUS_POLL_INTERVAL)
            }
        }

    /**
     * The request's effective status right now. [rowUuid] is null for a phantom ticket (either a
     * genuine unknown-address ticket, whose signature verifies but was never persisted, or a
     * malformed/forged one) — in both cases there is deliberately no DB lookup at all, only the
     * phantom shape, so a garbage ticket id can never coincide with — and thereby read out —
     * some other real row.
     *
     * Expiry is checked inline against [Clock], not a persisted sweep — matching [decide] and
     * [complete]'s "a missed purge must never resurrect a request" rule, so a `PENDING`/`APPROVED`
     * row past its `expires_at` reads as `EXPIRED` here even though no row was ever written with
     * that status.
     */
    private suspend fun currentStatus(
        rowUuid: String?,
        phantomExpiresAt: Long,
    ): PasswordResetStatusEvent {
        val row =
            rowUuid?.let { id ->
                suspendTransaction(db) {
                    db.passwordResetRequestsQueries.selectById(id).executeAsOneOrNull()
                }
            }
        val now = clock.now().toEpochMilliseconds()
        if (row == null) {
            val status = if (now >= phantomExpiresAt) PasswordResetStatus.EXPIRED else PasswordResetStatus.PENDING
            return PasswordResetStatusEvent(status = status, expiresAt = phantomExpiresAt)
        }
        val liveButPastExpiry = row.status in LIVE_STATES && row.expires_at <= now
        val status = if (liveButPastExpiry) PasswordResetStatus.EXPIRED else PasswordResetStatus.valueOf(row.status)
        return PasswordResetStatusEvent(status = status, expiresAt = row.expires_at)
    }

    /**
     * Signs [rowUuid] and [issuedAt] into the opaque, client-facing ticket id:
     * `"<rowUuid>.<issuedAtMs>.<sig>"`. The row's own DB primary key stays a plain UUID — this
     * composite exists only so the holder's ticket carries its own issue time, authenticated.
     *
     * That is the fix for the enumeration oracle a bare `rowUuid` would otherwise leave open: a
     * phantom (unknown-address) ticket has no row to read an `expiresAt` from, so
     * [observeStatus] used to mint a fresh phantom expiry at *subscribe* time — which, compared
     * against the `expiresAt` the caller already holds from [request]'s response, reveals
     * whether the address was real with a single delayed probe. Embedding the real issue time
     * in the ticket itself means [observeStatus] recomputes the *same* phantom expiry no matter
     * when it's asked, exactly mirroring a real row's fixed `expires_at`.
     */
    private fun mintTicketId(
        rowUuid: String,
        issuedAt: Long,
    ): String {
        val sig = hasher.hash(TICKET_DOMAIN + rowUuid + "." + issuedAt).take(TICKET_SIG_LENGTH)
        return "$rowUuid.$issuedAt.$sig"
    }

    /**
     * Recovers the DB row id a ticket id refers to, or `null` for a phantom/malformed one.
     * Exposed only so tests can query the row directly by its real primary key — mirrors what
     * [decide]/[complete]/[observeStatus] already do internally via [parseTicketId].
     */
    internal fun rowIdFor(ticketId: String): String? = parseTicketId(ticketId)?.rowUuid

    /**
     * Recovers the row id and issue time a well-formed, correctly-signed ticket id carries, or
     * `null` for anything else (wrong shape, forged signature). `null` is not an error condition
     * here — every caller of this treats it as "behave exactly like a legitimate phantom",
     * never as a distinct failure, so there is nothing for a malformed id to leak.
     */
    private fun parseTicketId(ticketId: String): ParsedTicketId? {
        val parts = ticketId.split(".")
        if (parts.size != 3) return null
        val (rowUuid, issuedAtRaw, sig) = parts
        val issuedAt = issuedAtRaw.toLongOrNull() ?: return null
        val expectedSig = hasher.hash(TICKET_DOMAIN + rowUuid + "." + issuedAt).take(TICKET_SIG_LENGTH)
        if (sig != expectedSig) return null
        return ParsedTicketId(rowUuid = rowUuid, issuedAt = issuedAt)
    }

    companion object {
        /** How long a request stays live. Short enough to bound exposure, long enough to phone someone. */
        internal val TTL = 15.minutes

        /** Wrong-code budget. Spending it kills the request. */
        internal const val MAX_ATTEMPTS = 5

        // Statuses that make [complete] report ResetRequestNotFound on read, matching that
        // error's contract ("unknown, expired, or already consumed... collapse to one shape").
        // DENIED is deliberately excluded — see the comment at its call site in [complete].
        private val TERMINAL_STATES = setOf("CONSUMED", "EXPIRED")

        // Statuses a row can still transition out of — the ones [currentStatus] must re-check
        // against [Clock] on every poll, since a live row can silently pass its `expires_at`
        // without ever being written as EXPIRED (matching decide()/complete()'s no-sweep rule).
        private val LIVE_STATES = setOf("PENDING", "APPROVED")

        // Re-check cadence for [observeStatus]'s poll — mirrors
        // [com.calypsan.listenup.server.auth.RegistrationStatusWatch]'s STATUS_RECHECK_INTERVAL_MILLIS.
        private val STATUS_POLL_INTERVAL = 3.seconds

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

        // Signs the client-facing ticket id so it can carry its own issue time — see the KDoc
        // on [mintTicketId]/[parseTicketId] for why that's load-bearing.
        internal const val TICKET_DOMAIN = "listenup:reset-ticket:"

        // Tamper-evidence, not a secret — 64 bits is ample to make forging a signature
        // infeasible while keeping the ticket id short.
        private const val TICKET_SIG_LENGTH = 16
    }
}

/** The row id and issue time recovered from a parsed client-facing ticket id. */
private data class ParsedTicketId(
    val rowUuid: String,
    val issuedAt: Long,
)

/**
 * Result of the [PasswordResetService.complete] transaction, folded to an [AppResult] once
 * outside it. Keeping this as data rather than throwing/returning directly from inside the
 * transaction lambda is what lets the whole read → verify → write sequence stay a single
 * expression with one exit per branch.
 */
private sealed interface CompletionOutcome {
    /** Unknown, expired, or already-consumed — indistinguishable by design. */
    data object NotFound : CompletionOutcome

    /** The attempt budget is spent; the request is dead regardless of what was just typed. */
    data object Exhausted : CompletionOutcome

    /** Still pending, or denied — both read as "not approved" to the requesting device. */
    data object NotApproved : CompletionOutcome

    /** Claim and/or code did not match. [remaining] is what's left of the attempt budget. */
    data class Wrong(
        val remaining: Int,
    ) : CompletionOutcome

    /** Both factors matched; the password was rewritten and the request consumed. */
    data class Consumed(
        val userId: String,
    ) : CompletionOutcome
}
