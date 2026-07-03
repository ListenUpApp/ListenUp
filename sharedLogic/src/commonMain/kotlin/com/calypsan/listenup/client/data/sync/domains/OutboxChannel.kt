package com.calypsan.listenup.client.data.sync.domains

import kotlinx.serialization.KSerializer

/**
 * A declared client→server write channel: the outbox identity (the queue's
 * `domainName`), the queued payload's wire format, and the [OpKind]s the domain
 * has declared. [OutboxChannels.all] is the one rulebook the sender map,
 * [com.calypsan.listenup.client.data.sync.OfflineEditor], and the queue's op
 * validation are being unified onto — no hand-coordinated string keys. This
 * declares the vocabulary; the sender/queue derivation lands in follow-up tasks
 * (until then the parallel `EditableDomain` list still drives those paths).
 */
internal class OutboxChannel<T : Any>(
    val name: String,
    val serializer: KSerializer<T>,
    val ops: Set<OpKind>,
)
