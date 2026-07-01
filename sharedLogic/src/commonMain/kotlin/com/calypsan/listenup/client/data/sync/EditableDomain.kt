package com.calypsan.listenup.client.data.sync

import kotlinx.serialization.KSerializer

/**
 * The one definition of a domain's offline editability: its outbox routing [name] and the
 * [serializer] for its patch DTO.
 *
 * Both halves of the offline-edit pattern read this single value instead of re-encoding the
 * facts independently: the write half ([OfflineEditor]) uses it to stamp the queued op and
 * encode the payload; the push half ([RpcUpdateOpSender]) uses it to decode the payload on
 * drain. Because [name] and [serializer] live in exactly one place, the enqueue side and the
 * drain side cannot disagree — a mismatch used to route an edit to "no sender registered" and
 * silently drop it past the retry ceiling.
 */
internal data class EditableDomain<T : Any>(
    val name: String,
    val serializer: KSerializer<T>,
)
