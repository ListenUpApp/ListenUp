package com.calypsan.listenup.client.data.sync

import kotlinx.serialization.KSerializer

/**
 * The one definition of a domain's offline editability: its outbox routing [name] and the
 * [serializer] for its patch DTO.
 *
 * The write half ([OfflineEditor]) uses it to stamp the queued op and encode the payload. The
 * push half now reads the paired [com.calypsan.listenup.client.data.sync.domains.OutboxChannel]
 * of the same [name] instead — the two declarations are kept name/serializer-aligned by
 * convention pending their unification onto one source.
 */
internal data class EditableDomain<T : Any>(
    val name: String,
    val serializer: KSerializer<T>,
)
