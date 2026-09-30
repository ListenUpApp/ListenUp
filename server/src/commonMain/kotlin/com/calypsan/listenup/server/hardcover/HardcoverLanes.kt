package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val log = loggerFor<HardcoverLanes>()

private val MAX_SLEEP: Duration = 1.hours
private val CRASH_RETRY: Duration = 5.minutes

/** What a lane does after one step. */
internal sealed interface LaneStep {
    /** Step again now. */
    data object Continue : LaneStep

    /** Nothing to do before [untilMs] (epoch ms) — or before a nudge. */
    data class Sleep(
        val untilMs: Long,
    ) : LaneStep

    /** Nothing more to do: the lane retires until the next nudge. */
    data object Stop : LaneStep
}

/**
 * One sequential coroutine lane per user — the shape Hardcover push and pull share. [nudge] starts a
 * user's lane, or wakes a sleeping one. The lane runs [step] until it answers [LaneStep.Stop],
 * sleeping on [LaneStep.Sleep] until that time or the next nudge; a step that throws is retried after
 * five minutes. A lane never retires with a nudge unserved. Lanes run in the scope given to [bind]
 * (the application's, cancelled at shutdown); before [bind] a nudge is a no-op.
 */
internal class HardcoverLanes(
    private val name: String,
    private val clock: Clock,
    private val step: suspend (userId: String) -> LaneStep,
) {
    private val lock = SynchronizedObject()
    private val lanes = HashMap<String, Channel<Unit>>()
    private var scope: CoroutineScope? = null

    fun bind(scope: CoroutineScope) {
        synchronized(lock) { this.scope = scope }
    }

    fun nudge(userId: String) {
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

    private suspend fun runLane(
        userId: String,
        wake: Channel<Unit>,
    ) {
        try {
            while (true) {
                val next =
                    runCatchingCancellable { step(userId) }.getOrElse { e ->
                        log.warn(e) { "hardcover $name lane step failed user=$userId; retrying later" }
                        LaneStep.Sleep(now() + CRASH_RETRY.inWholeMilliseconds)
                    }
                when (next) {
                    LaneStep.Continue -> {
                        continue
                    }

                    is LaneStep.Sleep -> {
                        withTimeoutOrNull((next.untilMs - now()).coerceIn(0L, MAX_SLEEP.inWholeMilliseconds)) { wake.receive() }
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

    private fun now() = clock.now().toEpochMilliseconds()
}
