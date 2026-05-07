package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry in a book's series membership list.
 *
 * [sequence] is a `String?` (not `Int`) per ABS invariant #10 — series sequences
 * like `"1.5"`, `"0a"`, and `"III"` are valid in the wild. Null when the series
 * membership is acknowledged but the position isn't known.
 */
@Serializable
@SerialName("SeriesEntry")
data class SeriesEntry(
    val name: String,
    val sequence: String?,
)
