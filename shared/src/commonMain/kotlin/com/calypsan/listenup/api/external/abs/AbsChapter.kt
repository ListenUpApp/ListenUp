package com.calypsan.listenup.api.external.abs

import kotlinx.serialization.Serializable

/**
 * One chapter as ABS encodes it in `metadata.json`. Times are in seconds.
 * Phase 2 reads but does not use these — chapter inference lands in Phase 3
 * once audiometa is ported.
 */
@Serializable
data class AbsChapter(
    val id: Int? = null,
    val start: Double,
    val end: Double,
    val title: String,
)
