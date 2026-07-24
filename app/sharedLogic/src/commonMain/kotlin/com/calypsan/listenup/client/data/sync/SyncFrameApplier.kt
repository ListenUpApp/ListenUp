package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Applies the [SyncFrame]s an RPC mutation returned (via `Mutated`) into Room, giving the
 * originating device read-your-writes for free.
 *
 * Each frame routes to its domain's registered [SyncDomainHandler] and is applied through the
 * SAME [SyncDomainHandler.onEvent] path that consumes every other client's firehose echo — the
 * generic, per-domain, revision-guarded, idempotent mirror upsert. There is no per-field or
 * per-domain code here: a new domain adopting echo-in-response works the instant its handler is
 * registered.
 *
 * **Why [SyncDomainHandler.onEvent] directly, not [SyncEventDispatcher.handle]:** the dispatcher
 * also advances the firehose STREAM cursor, which must stay owned by the live stream. `onEvent`
 * applies to Room under its own row-level revision guard and leaves the stream cursor untouched, so
 * the later firehose frame for the same revision still arrives (and no-ops), and resume/reconnect
 * are unaffected. The row guard also makes a double-apply (response now, echo later) safe: the
 * second `onEvent` at the same revision is skipped or re-upserts identically.
 */
internal class SyncFrameApplier(
    private val registry: ClientSyncDomainRegistry,
) {
    /** Route and apply every frame in [frames]. Never throws (except on cancellation). */
    suspend fun apply(frames: List<SyncFrame>) {
        for (frame in frames) {
            applyOne(frame)
        }
    }

    private suspend fun applyOne(frame: SyncFrame) {
        val handler =
            registry.lookup(frame.domain) ?: run {
                logger.debug { "No handler registered for domain '${frame.domain}'; dropping mutation frame" }
                return
            }

        @Suppress("UNCHECKED_CAST")
        val typed = handler as SyncDomainHandler<Any>
        val event =
            try {
                contractJson.decodeFromString(SyncEvent.serializer(typed.payloadSerializer), frame.json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    e,
                ) { "Failed to decode mutation frame for domain '${frame.domain}'; the firehose echo will heal it" }
                return
            }
        // A Failure here is not fatal: the row is left for the firehose/catch-up to redeliver, exactly
        // as when a firehose apply fails. Read-your-writes is a latency optimisation, not the only path.
        typed.onEvent(event)
    }
}
