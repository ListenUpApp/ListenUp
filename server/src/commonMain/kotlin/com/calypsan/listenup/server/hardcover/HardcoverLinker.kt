package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.util.KeyedMutex
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** RFC 8628 §3.5: on `slow_down`, the interval grows by five seconds for this and every later poll. */
private val SLOW_DOWN_STEP_MS = 5.seconds.inWholeMilliseconds

/**
 * Connects and disconnects a user's Hardcover account, and publishes each user's connection state.
 *
 * The device sign-in is polled HERE, in [applicationScope], not by the client: the user approves on
 * another device or browser tab, and the app that asked may be closed or asleep by then. Polling on
 * the server means approving always completes the connection, and every open client learns of it
 * through [observe]. [applicationScope] is cancelled at shutdown, which ends any poll in flight.
 *
 * States are held per user in memory, seeded lazily from [HardcoverConnectionStore], so one user's
 * state never reaches another's stream.
 *
 * Every token Hardcover issues ends up either stored or revoked, never orphaned: a disconnect and a
 * refresh ([HardcoverTokenProvider]) serialize on the same per-user lock ([withUserLock]), so a
 * disconnect that arrives mid-refresh revokes the pair the refresh just committed; and a grant whose
 * owner can't be looked up is revoked rather than dropped.
 *
 * Sync health lives outside a connection's lifecycle — a push landing, a pull catching up, an error
 * passing its cap, a Sync now starting or ending — so the linker also republishes Connected each time
 * [HardcoverSyncActivity.changes] names a user it holds a state for. A republish only ever replaces
 * Connected with Connected: it never overwrites a sign-in in progress, a break or a disconnect.
 */
class HardcoverLinker(
    private val oauth: HardcoverOAuthClient,
    private val graphQl: HardcoverGraphQlClient,
    private val store: HardcoverConnectionStore,
    private val applicationScope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val activity: HardcoverSyncActivity = HardcoverSyncActivity(),
) {
    private val lock = SynchronizedObject()
    private val states = HashMap<String, MutableStateFlow<HardcoverConnection>>()
    private val pollJobs = HashMap<String, Job>()
    private val userLocks = KeyedMutex()
    private val connected =
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The id of each user whose sign-in completes, as it completes. A new connection can make
     * Hardcover ratings runnable, so the ratings backfill listens here rather than waiting for the
     * nightly sweep to notice every book Hardcover has never tried.
     */
    val connections: SharedFlow<String> = connected.asSharedFlow()

    init {
        applicationScope.launch { activity.changes.collect { republish(it) } }
    }

    /**
     * Runs [block] holding [userId]'s connection lock — the one [disconnect] holds while it revokes
     * and deletes. [HardcoverTokenProvider] refreshes under it, so the two never interleave. Not
     * reentrant: [block] must not call [disconnect].
     */
    suspend fun <T> withUserLock(
        userId: String,
        block: suspend () -> T,
    ): T = userLocks.withLock(userId, block)

    /**
     * Starts a device sign-in for [userId] and returns the code to show them. A broken connection may
     * be relinked (the new one replaces it on success); a healthy one is [HardcoverError.AlreadyConnected].
     * Starting again while a sign-in is pending abandons the earlier code for the new one.
     */
    suspend fun start(userId: String): AppResult<HardcoverLinkPrompt> {
        if (store.connectionFor(userId) is StoredConnection.Healthy) {
            return AppResult.Failure(HardcoverError.AlreadyConnected())
        }
        val authorization =
            when (val started = oauth.startDeviceAuthorization()) {
                is DeviceAuthorizationResult.Unavailable -> {
                    return AppResult.Failure(HardcoverError.Unavailable(debugInfo = started.detail))
                }

                is DeviceAuthorizationResult.Started -> {
                    started.authorization
                }
            }
        val prompt =
            HardcoverLinkPrompt(
                userCode = authorization.userCode,
                verificationUri = authorization.verificationUri,
                verificationUriComplete = authorization.verificationUriComplete,
                expiresAt = nowMs() + authorization.expiresInSeconds.seconds.inWholeMilliseconds,
            )
        val state = stateFor(userId)
        val poll =
            applicationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    state.value = pollUntilSettled(userId, authorization, prompt.expiresAt)
                } finally {
                    forgetPollJob(userId, coroutineContext.job)
                }
            }
        // The previous poll is fully stopped before Linking is published, so it can never overwrite it.
        synchronized(lock) { pollJobs.put(userId, poll) }?.cancelAndJoin()
        state.value = HardcoverConnection.Linking(prompt)
        poll.start()
        return AppResult.Success(prompt)
    }

    /** [userId]'s live connection state. */
    suspend fun observe(userId: String): StateFlow<HardcoverConnection> = stateFor(userId).asStateFlow()

    /**
     * Disconnects [userId]: stops any pending sign-in, asks Hardcover to revoke the refresh token and
     * then the access token, deletes the row, and reports NotConnected. The revokes are best effort:
     * a failure or an outage on Hardcover's side never keeps someone connected against their wish.
     * The row is read, revoked and deleted under [withUserLock], so an in-flight refresh commits its
     * rotated pair first and that pair is the one revoked. Idempotent.
     */
    suspend fun disconnect(userId: String) {
        synchronized(lock) { pollJobs.remove(userId) }?.cancelAndJoin()
        withUserLock(userId) {
            store.connectionFor(userId)?.credentials?.let { revokeBestEffort(it.accessToken, it.refreshToken) }
            store.delete(userId)
            stateFor(userId).value = HardcoverConnection.NotConnected()
            activity.forget(userId)
        }
    }

    /**
     * Publishes that [userId]'s connection, as [hardcoverUsername], broke for [reason]. The token
     * provider calls this after marking the row.
     */
    suspend fun onBroken(
        userId: String,
        reason: HardcoverBrokenReason,
        hardcoverUsername: String,
    ) {
        stateFor(userId).value = HardcoverConnection.Broken(reason, hardcoverUsername)
    }

    /**
     * Marks [userId]'s connection broken for [reason] and publishes it — unless there is no connection
     * any more (a disconnect got there first). Under [withUserLock], so it never interleaves a
     * disconnect. Answers whether it broke anything. Push and pull both break a connection this way.
     */
    suspend fun breakIfConnected(
        userId: String,
        reason: HardcoverBrokenReason,
    ): Boolean {
        val username =
            withUserLock(userId) {
                val name =
                    when (val stored = store.connectionFor(userId)) {
                        is StoredConnection.Healthy -> stored.hardcoverUsername
                        is StoredConnection.Broken -> stored.hardcoverUsername
                        null -> null
                    }
                if (name != null) store.markBroken(userId, reason)
                name
            } ?: return false
        onBroken(userId, reason, username)
        activity.forget(userId)
        return true
    }

    /**
     * Polls until the sign-in settles, and returns the state it settled into. The first poll waits a
     * full interval, as RFC 8628 asks. A transient failure keeps polling; the code's own deadline
     * bounds the loop.
     */
    private suspend fun pollUntilSettled(
        userId: String,
        authorization: DeviceAuthorization,
        expiresAt: Long,
    ): HardcoverConnection {
        var intervalMs = authorization.intervalSeconds.seconds.inWholeMilliseconds
        while (true) {
            delay(intervalMs)
            if (nowMs() >= expiresAt) return HardcoverConnection.NotConnected(HardcoverLinkFailure.EXPIRED)
            when (val poll = oauth.pollToken(authorization.deviceCode)) {
                TokenPoll.Pending, is TokenPoll.Unavailable -> Unit
                TokenPoll.SlowDown -> intervalMs += SLOW_DOWN_STEP_MS
                TokenPoll.Denied -> return HardcoverConnection.NotConnected(HardcoverLinkFailure.DENIED)
                TokenPoll.Expired -> return HardcoverConnection.NotConnected(HardcoverLinkFailure.EXPIRED)
                is TokenPoll.Granted -> return connect(userId, poll.tokens)
            }
        }
    }

    /**
     * Learns who granted [tokens] and stores the connection. Without an owner there is nothing to
     * store, so the just-issued tokens are revoked rather than left live on Hardcover with no record.
     */
    private suspend fun connect(
        userId: String,
        tokens: HardcoverTokens,
    ): HardcoverConnection =
        when (val me = graphQl.me(tokens.accessToken)) {
            is MeResult.Found -> {
                store.save(userId, me.me, tokens).also { connected.tryEmit(userId) }
            }

            MeResult.Unauthorized, is MeResult.Unavailable -> {
                revokeBestEffort(tokens.accessToken, tokens.refreshToken)
                HardcoverConnection.NotConnected(HardcoverLinkFailure.UNREACHABLE)
            }
        }

    /** Revokes the refresh token, then the access token. Best effort: a failure never blocks the caller. */
    private suspend fun revokeBestEffort(
        accessToken: String,
        refreshToken: String,
    ) {
        listOf(refreshToken, accessToken).forEach { token -> runCatchingCancellable { oauth.revoke(token) } }
    }

    /** Drops [job] from the registry — unless a newer sign-in has already replaced it. */
    private fun forgetPollJob(
        userId: String,
        job: Job,
    ) = synchronized(lock) {
        if (pollJobs[userId] === job) pollJobs.remove(userId)
    }

    private suspend fun stateFor(userId: String): MutableStateFlow<HardcoverConnection> {
        synchronized(lock) { states[userId] }?.let { return it }
        val seeded = decorate(userId, store.connectionState(userId))
        return synchronized(lock) { states.getOrPut(userId) { MutableStateFlow(seeded) } }
    }

    /** Re-reads [userId]'s row and republishes it — only over a Connected, and only as a Connected. */
    private suspend fun republish(userId: String) {
        val state = synchronized(lock) { states[userId] } ?: return
        val fresh = decorate(userId, store.connectionState(userId))
        state.update { current ->
            if (current is HardcoverConnection.Connected && fresh is HardcoverConnection.Connected) fresh else current
        }
    }

    /** [connection] with what's in flight: a running Sync now, and a failed one, which outranks any stored problem. */
    private fun decorate(
        userId: String,
        connection: HardcoverConnection,
    ): HardcoverConnection =
        if (connection !is HardcoverConnection.Connected) {
            connection
        } else {
            connection.copy(
                isSyncing = activity.isSyncing(userId),
                syncProblem =
                    if (activity.syncNowFailed(userId)) HardcoverSyncProblem.SYNC_NOW_FAILED else connection.syncProblem,
            )
        }

    private fun nowMs() = clock.now().toEpochMilliseconds()
}
