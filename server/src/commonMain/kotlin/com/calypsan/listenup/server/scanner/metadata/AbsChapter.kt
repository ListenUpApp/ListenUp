package com.calypsan.listenup.server.scanner.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One chapter as ABS encodes it in `metadata.json`. Times are in seconds.
 * Currently read but not yet used — chapter inference is future work.
 */
@Serializable
data class AbsChapter(
    @SerialName("id")
    val id: Int? = null,
    val start: Double,
    val end: Double,
    val title: String,
)
