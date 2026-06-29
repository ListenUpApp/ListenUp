package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One audio file in playback order, with its derived position metadata.
 * Track/disc are currently filled from filename or parent folder only; embedded-tag
 * sources are future work and use [TrackNumberSource.METADATA].
 *
 * [durationMs] is the track's own playable length, parsed from embedded metadata
 * for multi-file books (where it is needed to sum the book's total duration and to
 * give each `book_audio_files` row its real length). It is `null` for single-file
 * books, where the book-level embedded duration already equals the one file's length.
 */
@Serializable
data class TrackEntry(
    @SerialName("file")
    val file: FileEntry,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val trackSource: TrackNumberSource? = null,
    val discSource: TrackNumberSource? = null,
    val durationMs: Long? = null,
)

/**
 * Where a [TrackEntry]'s track or disc number was derived from. Recorded so the UI can
 * indicate certainty (embedded metadata is stronger than a filename guess) and so the
 * scanner can skip re-deriving values it already has on incremental rescans.
 */
@Serializable
enum class TrackNumberSource {
    FILENAME,
    FOLDER,
    METADATA,
}
