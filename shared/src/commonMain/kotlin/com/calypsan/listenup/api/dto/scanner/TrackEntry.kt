package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One audio file in playback order, with its derived position metadata.
 * Phase 2 only fills track/disc from filename or parent folder; embedded-tag
 * sources land in Phase 3 and use [TrackNumberSource.METADATA].
 */
@Serializable
data class TrackEntry(
    @SerialName("file")
    val file: FileEntry,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val trackSource: TrackNumberSource? = null,
    val discSource: TrackNumberSource? = null,
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
