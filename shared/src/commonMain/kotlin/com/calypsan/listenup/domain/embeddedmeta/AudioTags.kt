package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.Serializable

/**
 * Embedded textual metadata extracted from an audio file's tag area.
 *
 * Sources: ID3v2/ID3v1 frames (MP3), Vorbis comments (FLAC, Ogg, Opus),
 * MP4 `ilst` atoms (M4A, M4B). The mapping from format-specific frame/key
 * names to these fields lives in the per-format parser; see spec §8 for
 * the full mapping tables.
 *
 * [custom] surfaces every unmapped tag key/value the parser saw, rather than
 * silently dropping them — a downstream phase can use them later without
 * re-reading the file. Empty map when the file has no unmapped tags.
 */
@Serializable
data class AudioTags(
    val title: String?,
    val subtitle: String?,
    val authors: List<String>,
    val narrators: List<String>,
    val series: List<SeriesEntry>,
    val genres: List<String>,
    val description: String?,
    val publisher: String?,
    val publishedYear: Int?,
    val asin: String?,
    val isbn: String?,
    val language: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val custom: Map<String, String>,
)
