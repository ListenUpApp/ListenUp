package com.calypsan.listenup.api.sync

import kotlinx.serialization.Serializable

/**
 * Type-safe wrapper around the global sync revision counter.
 *
 * Clients persist this across reconnects and pass it as `Last-Event-Id` on SSE
 * resume or as the `?since=` query parameter on REST catch-up. The underlying
 * type is [Long]; the value class is for call-site clarity only.
 */
@Serializable
@JvmInline
value class SyncCursor(
    val revision: Long,
)
