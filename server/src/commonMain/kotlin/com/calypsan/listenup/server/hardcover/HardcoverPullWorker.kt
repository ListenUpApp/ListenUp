package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = loggerFor<HardcoverPullWorker>()

/** How often a connected user's Hardcover shelf is pulled (spec B3). */
val PULL_INTERVAL: Duration = 15.minutes

/** A client coming to the foreground pulls only when the last pull caught up longer ago than this. */
val PULL_STALE_AFTER: Duration = 2.minutes

/** The attempt at which a failing pull's error shows on the connection. It keeps retrying regardless. */
internal const val PULL_MAX_ATTEMPTS = 8

private val PULL_THROTTLE_BACKOFF_BASE: Duration = 30.seconds
private val PULL_FAILURE_BACKOFF_BASE: Duration = 1.minutes
private val PULL_TOKEN_RETRY: Duration = 5.minutes

/** What asks the pull to run: the two RPCs, and a book whose match changed. */
interface HardcoverPullRequests {
    /**
     * "Sync now": a full pull at once (so a deletion on Hardcover shows straight away), and the push
     * lane woken. Returns as soon as that is queued. Answers NotConnected or ConnectionBroken when
     * there's no working connection to use.
     */
    suspend fun syncNow(userId: String): AppResult<Unit>

    /** A client came to the foreground: pull what changed, unless the last pull caught up within [PULL_STALE_AFTER]. Never fails. */
    suspend fun syncIfStale(userId: String)

    /** [bookId]'s match changed: forget the reads pulled through the old match, and re-pull the whole shelf. */
    suspend fun onMatchChanged(
        userId: String,
        bookId: String,
    )
}

/**
 * Runs the pull (spec B3): one lane per connected user ([HardcoverLanes]), pulling a page per step
 * through [HardcoverPuller] every [PULL_INTERVAL], and at once when asked ([HardcoverPullRequests]).
 * Each step is the user's only Hardcover conversation for its duration ([HardcoverUserGate], shared
 * with [HardcoverPushWorker]), and honours the pause either direction's 429 set. The schedule lives in
 * memory: after a restart every connected user is pulled once at boot.
 *
 * The error policy mirrors push's: 429/503 wait out `Retry-After` (or back off) and pause push too;
 * other failures back off from a minute up to [PULL_INTERVAL], and from [PULL_MAX_ATTEMPTS] the error
 * shows on the connection; 401 refreshes once, and a second 401 is `Broken(REVOKED)`; 403
 * `insufficient_scope` is `Broken(MISSING_SCOPE)`. A lane stops only when there's no working connection.
 *
 * A request that lands while a page is in flight is never lost: the pull it asks for stays due when
 * that page's step reschedules, and a full pull asked for ([syncNow], [onMatchChanged]) is written at
 * once and written again inside the gate before the next page, so a full pull already running can't
 * complete over the request and quietly turn it into an incremental one.
 */
class HardcoverPullWorker(
    private val puller: HardcoverPuller,
    private val store: HardcoverPullStore,
    private val tokens: HardcoverTokenProvider,
    private val connections: HardcoverConnectionStore,
    private val linker: HardcoverLinker,
    private val gate: HardcoverUserGate,
    private val pushNudge: HardcoverPushNudge,
    private val clock: Clock = Clock.System,
) : HardcoverPullRequests {
    private val lock = SynchronizedObject()
    private val dueAt = HashMap<String, Long>()
    private val caughtUpAt = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()
    private val refreshed = HashSet<String>()
    private val pullWanted = HashSet<String>()
    private val fullPullWanted = HashSet<String>()
    private val lanes = HardcoverLanes("pull", clock) { step(it) }

    /** Pulls every connected user now, and each user who connects from here on. */
    fun start(scope: CoroutineScope): Job {
        lanes.bind(scope)
        return scope.launch {
            connections.healthyUserIds().forEach(::pullNow)
            linker.connections.collect { pullNow(it) }
        }
    }

    override suspend fun syncNow(userId: String): AppResult<Unit> {
        when (connections.connectionFor(userId)) {
            null -> return AppResult.Failure(HardcoverError.NotConnected())

            is StoredConnection.Broken -> return AppResult.Failure(
                HardcoverError.ConnectionBroken(debugInfo = "syncNow"),
            )

            is StoredConnection.Healthy -> Unit
        }
        requestFullPull(userId)
        pullNow(userId)
        pushNudge.nudge(userId)
        return AppResult.Success(Unit)
    }

    override suspend fun syncIfStale(userId: String) {
        if (connections.connectionFor(userId) !is StoredConnection.Healthy) return
        val last = synchronized(lock) { caughtUpAt[userId] }
        if (last != null && now() - last < PULL_STALE_AFTER.inWholeMilliseconds) return
        pullNow(userId)
    }

    override suspend fun onMatchChanged(
        userId: String,
        bookId: String,
    ) {
        store.forgetPulledBook(userId, bookId)
        requestFullPull(userId)
        pullNow(userId)
    }

    /** One step of [userId]'s lane, as the user's only Hardcover conversation: pull a page if one is due, else say when one will be. */
    internal suspend fun step(userId: String): LaneStep = gate.withUser(userId) { stepHoldingGate(userId) }

    private suspend fun stepHoldingGate(userId: String): LaneStep {
        val now = now()
        gate.pausedUntil(userId)?.takeIf { it > now }?.let { return LaneStep.Sleep(it) }
        val due = synchronized(lock) { dueAt[userId] } ?: now
        if (due > now) return LaneStep.Sleep(due)
        // From here, a request made before this step is being served; one made during it is not.
        synchronized(lock) { pullWanted.remove(userId) }
        val token =
            when (val lookup = tokens.accessToken(userId)) {
                is TokenLookup.Valid -> {
                    lookup.accessToken
                }

                TokenLookup.Unavailable -> {
                    return LaneStep.Sleep(now + PULL_TOKEN_RETRY.inWholeMilliseconds)
                }

                TokenLookup.NotConnected, is TokenLookup.Broken -> {
                    forget(userId)
                    return LaneStep.Stop
                }
            }
        if (synchronized(lock) { fullPullWanted.remove(userId) }) store.requestFullPull(userId)
        val page = puller.pullPage(userId, token)
        if (page != HardcoverCall.Unauthorized) synchronized(lock) { refreshed.remove(userId) }
        return when (page) {
            is HardcoverCall.Ok -> {
                when (page.value) {
                    PullProgress.MORE_PAGES -> LaneStep.Continue
                    PullProgress.CAUGHT_UP -> caughtUp(userId)
                }
            }

            HardcoverCall.Unauthorized -> {
                onUnauthorized(userId, token)
            }

            is HardcoverCall.MissingScope -> {
                breakConnection(userId, HardcoverBrokenReason.MISSING_SCOPE)
            }

            is HardcoverCall.Throttled -> {
                val wait =
                    page.retryAfterMs
                        ?: exponentialBackoff(
                            bumpFailures(userId),
                            PULL_THROTTLE_BACKOFF_BASE,
                            PULL_INTERVAL,
                        ).inWholeMilliseconds
                gate.pause(userId, now + wait)
                retryAt(userId, now + wait)
            }

            is HardcoverCall.Failed -> {
                val attempts = bumpFailures(userId)
                if (attempts >= PULL_MAX_ATTEMPTS) connections.recordPullError(userId, page.detail)
                retryAt(
                    userId,
                    now + exponentialBackoff(attempts, PULL_FAILURE_BACKOFF_BASE, PULL_INTERVAL).inWholeMilliseconds,
                )
            }
        }
    }

    private suspend fun caughtUp(userId: String): LaneStep {
        val at = now()
        synchronized(lock) {
            caughtUpAt[userId] = at
            failures.remove(userId)
        }
        connections.markPulled(userId, at)
        return retryAt(userId, at + PULL_INTERVAL.inWholeMilliseconds)
    }

    /** Refresh once per pull; a second 401 after a refresh that worked means Hardcover won't take any token of ours. */
    private suspend fun onUnauthorized(
        userId: String,
        token: String,
    ): LaneStep {
        val firstRejection = synchronized(lock) { refreshed.add(userId) }
        if (!firstRejection) return breakConnection(userId, HardcoverBrokenReason.REVOKED)
        return when (tokens.refreshAfterRejection(userId, token)) {
            is TokenLookup.Valid -> {
                LaneStep.Continue
            }

            TokenLookup.Unavailable -> {
                // A refresh that couldn't reach Hardcover doesn't count.
                synchronized(lock) { refreshed.remove(userId) }
                LaneStep.Sleep(now() + PULL_TOKEN_RETRY.inWholeMilliseconds)
            }

            TokenLookup.NotConnected, is TokenLookup.Broken -> {
                forget(userId)
                LaneStep.Stop
            }
        }
    }

    private suspend fun breakConnection(
        userId: String,
        reason: HardcoverBrokenReason,
    ): LaneStep {
        forget(userId)
        if (linker.breakIfConnected(userId, reason)) {
            log.warn { "hardcover pull: connection for user=$userId broke ($reason); pulls wait for a reconnect" }
        }
        return LaneStep.Stop
    }

    private suspend fun requestFullPull(userId: String) {
        synchronized(lock) { fullPullWanted += userId }
        store.requestFullPull(userId)
    }

    private fun pullNow(userId: String) {
        synchronized(lock) {
            dueAt[userId] = now()
            pullWanted += userId
        }
        lanes.nudge(userId)
    }

    /** The next pull is at [at] — unless one was asked for while this step ran, which stays due now. */
    private fun retryAt(
        userId: String,
        at: Long,
    ): LaneStep =
        synchronized(lock) {
            if (userId in pullWanted) {
                LaneStep.Sleep(dueAt.getValue(userId))
            } else {
                dueAt[userId] = at
                LaneStep.Sleep(at)
            }
        }

    private fun bumpFailures(userId: String): Int =
        synchronized(lock) {
            ((failures[userId] ?: 0) + 1).also {
                failures[userId] =
                    it
            }
        }

    private fun forget(userId: String) {
        synchronized(lock) {
            dueAt.remove(userId)
            pullWanted.remove(userId)
            failures.remove(userId)
            refreshed.remove(userId)
        }
    }

    private fun now() = clock.now().toEpochMilliseconds()
}
