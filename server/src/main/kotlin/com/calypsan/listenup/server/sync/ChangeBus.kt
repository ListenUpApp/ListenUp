package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val LIVE_TAIL_BUFFER = 256

/**
 * Type-bound bus entry. The source repository travels alongside the event so
 * the consumer (SSE firehose, REST catch-up listener) can encode the event
 * using the repository's own serializer — no static-registry lookup required.
 *
 * The previous untyped shape (`BusEvent(domainName, event)`) relied on a
 * static registry to look up the right serializer at consumption
 * time. That coupling allowed a misrouted publish (wrong domain string, or
 * reflective misuse) to silently encode a payload through the wrong serializer,
 * producing malformed SSE frames and reconnect storms. The typed shape makes
 * the binding compile-checked: a `BusEvent<Tag>` literally cannot carry a
 * `Book` payload.
 */
data class BusEvent<T : Any>(
    val repo: SyncableRepository<T, *>,
    val event: SyncEvent<T>,
    val userId: String? = null,
)

/**
 * In-memory pub/sub for [BusEvent]s. Single bus per process, registered as a
 * Koin singleton with `createdAtStart()` so domain repositories' init blocks
 * can publish during application bootstrap.
 *
 * `replay = 256` retains the last 256 events for late subscribers and for
 * [oldestRetainedRevision] reads. `extraBufferCapacity = 0` means the replay
 * cache is the sole buffer; `BufferOverflow.DROP_OLDEST` evicts the head of
 * the replay cache when it is full, so a slow client gets
 * `SyncControl.CursorStale` and falls back to REST catch-up.
 *
 * [oldestRetainedRevision] reads directly from [MutableSharedFlow.replayCache]
 * so it always reflects the actual buffer floor, including after DROP_OLDEST
 * evictions.
 */
class ChangeBus {
    private val flow =
        MutableSharedFlow<BusEvent<*>>(
            replay = LIVE_TAIL_BUFFER,
            extraBufferCapacity = 0,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Publishes [event] onto the bus, paired with the source [repo] so consumers
     * can encode the payload through the repo's own serializer. The `<T>` binding
     * statically prevents publishing an event whose payload type doesn't match
     * the repo's element type.
     */
    suspend fun <T : Any> publish(
        repo: SyncableRepository<T, *>,
        event: SyncEvent<T>,
        userId: String? = null,
    ) {
        flow.emit(BusEvent(repo, event, userId))
    }

    fun subscribe(): SharedFlow<BusEvent<*>> = flow.asSharedFlow()

    /**
     * Best-effort lower bound on the oldest revision still in the live-tail
     * replay buffer. Returns null when the buffer is empty (no events
     * published since process start, or all events evicted under DROP_OLDEST).
     */
    fun oldestRetainedRevision(): Long? =
        flow.replayCache
            .firstOrNull()
            ?.event
            ?.revision
}
