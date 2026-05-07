package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.Serializable

/**
 * A single chapter inside an audiobook file.
 *
 * Times are milliseconds from the start of the audio stream. [endMs] is
 * derived for chapters that don't carry an explicit end (e.g. Nero `chpl`
 * encodes only starts; the parser computes ends as `next.startMs - 1`,
 * with the last chapter's end clamped to the file's `durationMs`).
 *
 * [index] is 1-based, matching ABS conventions.
 */
@Serializable
data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)
