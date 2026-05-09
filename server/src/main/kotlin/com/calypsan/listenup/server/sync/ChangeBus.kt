package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference

private const val LIVE_TAIL_BUFFER = 256

/**
 * In-memory pub/sub for [SyncEvent]s. Single bus per process, registered as a
 * Koin singleton with `createdAtStart()` so domain repositories' init blocks
 * can publish during application bootstrap.
 *
 * `replay = 0` because reconnect catch-up uses the database (REST `?since=<rev>`),
 * not the bus's internal buffer. `extraBufferCapacity = 256` is the live-tail
 * window for slow consumers; `BufferOverflow.DROP_OLDEST` means a slow client
 * gets `SyncControl.CursorStale` and falls back to REST.
 *
 * [oldestRetainedRevision] is updated heuristically — when an event is
 * published, the oldest tracked is set to the new event's revision if it's
 * the first; on overflow eviction we don't have a hook from MutableSharedFlow,
 * so the value is conservative — it tracks what we *might* still have. When
 * the buffer is empty (no subscribers, or just-emitted-and-collected), it's
 * null.
 */
class ChangeBus {
    private val flow =
        MutableSharedFlow<SyncEvent<*>>(
            replay = 0,
            extraBufferCapacity = LIVE_TAIL_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val oldest = AtomicReference<Long?>(null)

    suspend fun publish(event: SyncEvent<*>) {
        oldest.updateAndGet { current -> current ?: event.revision }
        flow.emit(event)
    }

    fun subscribe(): SharedFlow<SyncEvent<*>> = flow.asSharedFlow()

    /**
     * Best-effort lower bound on the oldest revision still in the live-tail
     * buffer. Used by the SSE endpoint to decide cursor-stale fallback.
     * Returns null when no events have been published since process start.
     */
    fun oldestRetainedRevision(): Long? = oldest.get()
}
