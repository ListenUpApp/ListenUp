package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.SerialName
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
@SerialName("AudioTags")
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
    /** Embedded sort title (ID3 `TSOT`, MP4 `sonm`); the authoritative sort form when present. */
    val titleSort: String? = null,
    /** Embedded sort form of the artist/author field (ID3 `TSOP`, MP4 `soar`); drives contributor identity. */
    val authorsSort: String? = null,
) {
    companion object {
        /** The [custom] map key under which every format reader stores the file's
         *  comment tag (ID3v2 COMM, MP4 `©cmt`, Vorbis COMMENT). The Analyzer uses it
         *  as the `description` fallback, matching the Go reference. */
        const val COMMENT_KEY: String = "comment"

        /** The [custom] map key under which every format reader stores the album tag
         *  (ID3 `TALB`, MP4 `©alb`, ID3v1 album). For a multi-file audiobook the album is
         *  the **book** title (the per-file [title] tag is the chapter title), so the Analyzer
         *  treats it as the authoritative book title — matching Audiobookshelf. */
        const val ALBUM_KEY: String = "album"
    }
}
