package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.Serializable

/**
 * One audio file in playback order, with its derived position metadata.
 * Phase 2 only fills track/disc from filename or parent folder; embedded-tag
 * sources land in Phase 3 and use [TrackNumberSource.METADATA].
 */
@Serializable
data class TrackEntry(
    val file: FileEntry,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val trackSource: TrackNumberSource? = null,
    val discSource: TrackNumberSource? = null,
)

@Serializable
enum class TrackNumberSource {
    FILENAME,
    FOLDER,
    METADATA,
}
