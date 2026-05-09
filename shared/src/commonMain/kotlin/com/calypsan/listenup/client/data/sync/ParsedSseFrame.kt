package com.calypsan.listenup.client.data.sync

/**
 * A single parsed SSE frame. `id:` lines drive the [com.calypsan.listenup.api.sync.SyncCursor]
 * so the engine can resume via `Last-Event-Id`; `event:` lines disambiguate
 * domain events (e.g. `"tags"`) from out-of-band controls (`"control"`); the
 * concatenated `data:` payload is the JSON body the dispatcher decodes.
 */
data class ParsedSseFrame(
    val id: Long?,
    val event: String?,
    val data: String,
)
