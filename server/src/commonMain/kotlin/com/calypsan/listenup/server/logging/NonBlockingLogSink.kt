package com.calypsan.listenup.server.logging

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * A log queue that callers can never block on. [offer] hands a line to a bounded buffer and returns;
 * a single coroutine in [scope] drains it through [write]. When the buffer is full the line is
 * dropped and counted, and once the writer has emptied the buffer it writes [droppedNotice] so the
 * gap is visible in the output rather than silent.
 *
 * Why: on 2026-09-12 and 2026-09-25 the NAS's log driver stopped reading the container's stdout, the
 * pipe filled, and the next log line blocked the thread that wrote it, taking playback, sync and
 * `/healthz` down with it. A log line is worth less than the request that produced it.
 *
 * [write] may block (a stalled stdout write does); only the drain coroutine waits on it, so run
 * [scope] on a thread of its own.
 */
internal class NonBlockingLogSink<T>(
    capacity: Int,
    scope: CoroutineScope,
    private val droppedNotice: ((Long) -> T)? = null,
    private val write: suspend (T) -> Unit,
) {
    private val lines = Channel<T>(capacity)
    private val dropped = atomic(0L)
    private val droppedTotal = atomic(0L)

    private val drain =
        scope.launch {
            while (true) {
                val line =
                    lines.tryReceive().getOrNull()
                        ?: run {
                            reportDrops()
                            lines.receiveCatching().getOrNull()
                        }
                        ?: break
                write(line)
            }
            reportDrops()
        }

    /** Queues [line] for writing, or drops it if the queue is full or closed. Never suspends or blocks. */
    fun offer(line: T) {
        if (lines.trySend(line).isFailure) {
            dropped.incrementAndGet()
            droppedTotal.incrementAndGet()
        }
    }

    /** Lines dropped since this sink was created. */
    fun droppedSoFar(): Long = droppedTotal.value

    /**
     * Stops accepting lines and waits up to [timeout] for the queue to be written. Returns false when
     * the output did not take everything in time, so a wedged stdout cannot hold shutdown hostage.
     */
    suspend fun closeAndDrain(timeout: Duration): Boolean {
        lines.close()
        return withTimeoutOrNull(timeout) { drain.join() } != null
    }

    private suspend fun reportDrops() {
        val notice = droppedNotice ?: return
        val count = dropped.getAndSet(0L)
        if (count > 0) write(notice(count))
    }
}
