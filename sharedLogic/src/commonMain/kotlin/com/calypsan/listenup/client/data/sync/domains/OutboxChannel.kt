package com.calypsan.listenup.client.data.sync.domains

import kotlinx.serialization.KSerializer

/**
 * A declared client→server write channel: the outbox identity (the queue's
 * `domainName`), the queued payload's wire format, and the [OpKind]s the domain
 * has declared. [OutboxChannels.all] is the one rulebook the sender map,
 * [com.calypsan.listenup.client.data.sync.OfflineEditor], and the queue's op
 * validation all derive from — no hand-coordinated string keys.
 *
 * @param idempotent whether re-firing an op on this channel after a provably-sent-but-unconfirmed
 *   drop ([com.calypsan.listenup.api.error.TransportError.OutcomeUnknown]) is safe — i.e. the server
 *   dedupes / last-write-wins, so a second send cannot double-apply. Idempotent → the outbox re-sends;
 *   non-idempotent → the op quarantines to the dead-letter queue for manual resolution.
 */
internal class OutboxChannel<T : Any>(
    val name: String,
    val serializer: KSerializer<T>,
    val ops: Set<OpKind>,
    val idempotent: Boolean,
)
