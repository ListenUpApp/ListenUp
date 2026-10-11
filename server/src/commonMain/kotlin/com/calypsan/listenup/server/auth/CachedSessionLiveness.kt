package com.calypsan.listenup.server.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One caller's session liveness, remembered for a connection. A live answer is trusted for [ttl]
 * so a chatty socket costs one session read per window, not one per call; a dead answer is final,
 * because a revoked session is never un-revoked.
 */
internal class CachedSessionLiveness(
    private val provider: PrincipalProvider,
    private val liveness: SessionLiveness,
    private val ttl: Duration = LIVENESS_TTL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private var dead = false
    private var liveUntil: TimeMark? = null

    /** Whether the caller's session is still live; false when it is revoked or there is no principal. */
    suspend fun isLive(): Boolean =
        mutex.withLock {
            if (dead) return@withLock false
            if (liveUntil?.hasNotPassedNow() == true) return@withLock true
            val sessionId = provider.current()?.sessionId
            val live = sessionId != null && liveness.isLive(sessionId, issuedAtEpochSeconds = null)
            if (live) liveUntil = timeSource.markNow() + ttl else dead = true
            live
        }

    internal companion object {
        val LIVENESS_TTL: Duration = 5.seconds
    }
}
