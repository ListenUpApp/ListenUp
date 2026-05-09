package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * REST catch-up response envelope. `items` are *current state* of domain rows
 * with `revision > <client cursor>`, soft-deleted rows included so clients can
 * apply tombstones. `nextCursor` is the highest `revision` in this page (null
 * when [items] is empty). `hasMore` indicates whether the server has more rows
 * beyond this page; clients page until `hasMore == false`.
 */
@Serializable
data class Page<T>(
    @SerialName("items")
    val items: List<T>,
    val nextCursor: Long?,
    val hasMore: Boolean,
)
