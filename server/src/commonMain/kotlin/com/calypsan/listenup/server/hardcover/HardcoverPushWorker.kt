package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = loggerFor<HardcoverPushWorker>()

/** The attempt at which a failing row's error is shown on the connection and retries slow to [CAPPED_RETRY_INTERVAL]. */
internal const val PUSH_MAX_ATTEMPTS = 8

private val THROTTLE_BACKOFF_BASE: Duration = 30.seconds
private val FAILURE_BACKOFF_BASE: Duration = 1.minutes
private val BACKOFF_CAP: Duration = 1.hours
private val CAPPED_RETRY_INTERVAL: Duration = 6.hours
private val TOKEN_RETRY: Duration = 5.minutes
private val MAX_SLEEP: Duration = 1.hours
private val CRASH_RETRY: Duration = 5.minutes

/** What a lane does after one [HardcoverPushWorker.step]. */
internal sealed interface LaneStep {
    /** Step again now. */
    data object Continue : LaneStep

    /** Nothing to do before [untilMs] (epoch ms) — or before a nudge. */
    data class Sleep(
        val untilMs: Long,
    ) : LaneStep

    /** Nothing more to do: every row is done or parked, or the connection can't be used. */
    data object Stop : LaneStep
}

/**
 * Drains `hardcover_outbox` (spec B2): one sequential lane per user, in outbox order, only while the
 * user's connection is healthy. Books are matched lazily ([HardcoverBookMatcher]) when their first row
 * comes up; each row then runs through [HardcoverPushExecutor]. The error policy:
 * - 429 / 503: reschedule after Hardcover's `Retry-After`, or exponential backoff, and pause the lane
 *   — the limits are per user;
 * - 408 / 500 / anything else: exponential backoff; from [PUSH_MAX_ATTEMPTS] the error shows on the
 *   connection and the row retries every six hours — never dropped;
 * - 401: refresh once and retry the row; a second 401 on it after a successful refresh is
 *   `Broken(REVOKED)`;
 * - 403 `insufficient_scope`: `Broken(MISSING_SCOPE)`.
 * A lane that runs out of work retires; a nudge ([HardcoverPushRecorder], a manual link, a reconnect,
 * boot) starts it again. Lanes run in the scope passed to [start], cancelled at shutdown. Each step
 * holds the user's [HardcoverUserGate], which the pull lane shares.
 */
class HardcoverPushWorker(
    private val outbox: HardcoverOutbox,
    private val links: HardcoverBookLinkStore,
    private val matcher: HardcoverBookMatcher,
    private val executor: HardcoverPushExecutor,
    private val tokens: HardcoverTokenProvider,
    private val connections: HardcoverConnectionStore,
    private val linker: HardcoverLinker,
    private val identities: HardcoverBookIdentities,
    private val gate: HardcoverUserGate,
    private val clock: Clock = Clock.System,
) : HardcoverPushNudge {
    private val lock = SynchronizedObject()
    private val lanes = HashMap<String, Channel<Unit>>()
    private val refreshedForRow = HashMap<String, Long>()
    private var scope: CoroutineScope? = null

    /** Starts a lane for every user with queued rows, and one for each user who reconnects. */
    fun start(scope: CoroutineScope): Job {
        synchronized(lock) { this.scope = scope }
        return scope.launch {
            outbox.usersWithPending().forEach(::nudge)
            linker.connections.collect { nudge(it) }
        }
    }

    /**
     * Wakes [userId]'s lane, starting one when none runs. Before [start] this is a no-op: the rows are
     * already in the outbox, and [start] starts a lane for every user who has any.
     */
    override fun nudge(userId: String) {
        synchronized(lock) {
            val running = lanes[userId]
            if (running != null) {
                running.trySend(Unit)
                return
            }
            val laneScope = scope ?: return
            val wake = Channel<Unit>(Channel.CONFLATED)
            lanes[userId] = wake
            laneScope.launch { runLane(userId, wake) }
        }
    }

    /** One step of [userId]'s lane: run (or match, or wait for) the next row, as the user's only Hardcover conversation. */
    internal suspend fun step(userId: String): LaneStep = gate.withUser(userId) { stepHoldingGate(userId) }

    private suspend fun stepHoldingGate(userId: String): LaneStep {
        val now = now()
        gate.pausedUntil(userId)?.takeIf { it > now }?.let { return LaneStep.Sleep(it) }
        val row = outbox.head(userId) ?: return outbox.nextWakeAt(userId)?.let { LaneStep.Sleep(it) } ?: LaneStep.Stop
        val token =
            when (val lookup = tokens.accessToken(userId)) {
                is TokenLookup.Valid -> lookup.accessToken
                TokenLookup.Unavailable -> return LaneStep.Sleep(now + TOKEN_RETRY.inWholeMilliseconds)
                TokenLookup.NotConnected, is TokenLookup.Broken -> return LaneStep.Stop
            }
        val link = links.linkFor(userId, row.bookId) ?: return matchLazily(row, token)
        // Unlinked since the head was read: the outbox now parks the row.
        if (!link.isLinked) return LaneStep.Continue
        return when (val outcome = executor.execute(row, link, token)) {
            PushOutcome.Done -> {
                outbox.complete(row.id)
                connections.markSynced(userId, now())
                forgetRefresh(userId)
                LaneStep.Continue
            }

            PushOutcome.Suppressed -> {
                forgetRefresh(userId)
                LaneStep.Continue
            }

            is PushOutcome.Failed -> {
                onFailure(row, token, outcome.failure)
            }
        }
    }

    private suspend fun runLane(
        userId: String,
        wake: Channel<Unit>,
    ) {
        try {
            while (true) {
                val step =
                    runCatchingCancellable { step(userId) }.getOrElse { e ->
                        log.warn(e) { "hardcover push lane step failed user=$userId; retrying later" }
                        LaneStep.Sleep(now() + CRASH_RETRY.inWholeMilliseconds)
                    }
                when (step) {
                    LaneStep.Continue -> {
                        Unit
                    }

                    is LaneStep.Sleep -> {
                        withTimeoutOrNull(
                            (step.untilMs - now()).coerceIn(0L, MAX_SLEEP.inWholeMilliseconds),
                        ) { wake.receive() }
                    }

                    LaneStep.Stop -> {
                        if (retire(userId, wake)) return
                    }
                }
            }
        } finally {
            // Cancelled (shutdown): unregister, so a registry entry never outlives its lane.
            synchronized(lock) { if (lanes[userId] === wake) lanes.remove(userId) }
        }
    }

    /** Retires [userId]'s lane — unless a nudge arrived since its last step, which it must serve first. */
    private fun retire(
        userId: String,
        wake: Channel<Unit>,
    ): Boolean =
        synchronized(lock) {
            if (wake.tryReceive().isSuccess) {
                false
            } else {
                lanes.remove(userId)
                true
            }
        }

    /** The row's book has never been matched: match it now; the next step pushes (or parks) it. */
    private suspend fun matchLazily(
        row: HardcoverOutboxRow,
        token: String,
    ): LaneStep {
        val identity = identities.identityOf(row.bookId)
        if (identity == null) {
            // Not dropped: the book may come back. It waits, visibly, like any other failing row.
            outbox.reschedule(
                row.id,
                row.attempts + 1,
                now() + CAPPED_RETRY_INTERVAL.inWholeMilliseconds,
                "book not in the library",
            )
            return LaneStep.Continue
        }
        val match = matcher.match(token, identity).valueOr { return onFailure(row, token, it) }
        links.recordAutomaticMatch(row.userId, row.bookId, match)
        return LaneStep.Continue
    }

    private suspend fun onFailure(
        row: HardcoverOutboxRow,
        token: String,
        failure: HardcoverCall<Nothing>,
    ): LaneStep {
        if (failure != HardcoverCall.Unauthorized) forgetRefresh(row.userId)
        val now = now()
        val attempts = row.attempts + 1
        return when (failure) {
            is HardcoverCall.Ok -> {
                LaneStep.Continue
            }

            HardcoverCall.Unauthorized -> {
                onUnauthorized(row, token)
            }

            is HardcoverCall.MissingScope -> {
                breakConnection(row.userId, HardcoverBrokenReason.MISSING_SCOPE)
            }

            is HardcoverCall.Throttled -> {
                val wait =
                    failure.retryAfterMs
                        ?: exponentialBackoff(attempts, THROTTLE_BACKOFF_BASE, BACKOFF_CAP).inWholeMilliseconds
                outbox.reschedule(row.id, attempts, now + wait, "throttled by Hardcover")
                gate.pause(row.userId, now + wait)
                LaneStep.Sleep(now + wait)
            }

            is HardcoverCall.Failed -> {
                val capped = attempts >= PUSH_MAX_ATTEMPTS
                val wait =
                    if (capped) {
                        CAPPED_RETRY_INTERVAL
                    } else {
                        exponentialBackoff(
                            attempts,
                            FAILURE_BACKOFF_BASE,
                            BACKOFF_CAP,
                        )
                    }
                if (capped) connections.recordPushError(row.userId, failure.detail)
                outbox.reschedule(row.id, attempts, now + wait.inWholeMilliseconds, failure.detail)
                LaneStep.Continue
            }
        }
    }

    /**
     * Refresh once per row; a second 401 on the same row after a refresh that worked means Hardcover
     * won't take any token of ours. A refresh that couldn't reach Hardcover doesn't count.
     */
    private suspend fun onUnauthorized(
        row: HardcoverOutboxRow,
        token: String,
    ): LaneStep {
        val refreshedAlready = synchronized(lock) { refreshedForRow[row.userId] == row.id }
        if (refreshedAlready) return breakConnection(row.userId, HardcoverBrokenReason.REVOKED)
        return when (tokens.refreshAfterRejection(row.userId, token)) {
            is TokenLookup.Valid -> {
                synchronized(lock) { refreshedForRow[row.userId] = row.id }
                LaneStep.Continue
            }

            TokenLookup.Unavailable -> {
                LaneStep.Sleep(now() + TOKEN_RETRY.inWholeMilliseconds)
            }

            TokenLookup.NotConnected, is TokenLookup.Broken -> {
                LaneStep.Stop
            }
        }
    }

    private suspend fun breakConnection(
        userId: String,
        reason: HardcoverBrokenReason,
    ): LaneStep {
        forgetRefresh(userId)
        if (linker.breakIfConnected(userId, reason)) {
            log.warn { "hardcover push: connection for user=$userId broke ($reason); pushes wait for a reconnect" }
        }
        return LaneStep.Stop
    }

    private fun forgetRefresh(userId: String) {
        synchronized(lock) { refreshedForRow.remove(userId) }
    }

    private fun now() = clock.now().toEpochMilliseconds()
}
