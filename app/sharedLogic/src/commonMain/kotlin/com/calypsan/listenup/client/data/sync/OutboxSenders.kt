package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels

/** Binds [channel] to its one RPC [push] as a typed sender-map entry. */
internal fun <T : Any> outboxBinding(
    channel: OutboxChannel<T>,
    push: suspend (entityId: String, payload: T) -> WireAppResult<*>,
): Pair<OutboxChannel<*>, PendingOperationSender> = channel to OutboxOpSender(channel, push)

/**
 * Builds the production [PendingOperationSender] from channel bindings, requiring
 * the map to bind EXACTLY the declared [OutboxChannels.all] — a missing or extra
 * binding fails at DI-graph construction instead of routing an op to "no sender"
 * five retries later.
 */
internal fun outboxSender(bindings: Map<OutboxChannel<*>, PendingOperationSender>): PendingOperationSender {
    val declared = OutboxChannels.all.map { it.name }.toSet()
    val bound = bindings.keys.map { it.name }.toSet()
    require(bound == declared) {
        "outbox sender map must bind exactly the declared channels; " +
            "missing=${declared - bound} extra=${bound - declared}"
    }
    return DomainPendingOperationSender(
        byDomain =
            bindings.entries.associate { (channel, sender) ->
                channel.name to
                    sender
            },
    )
}
