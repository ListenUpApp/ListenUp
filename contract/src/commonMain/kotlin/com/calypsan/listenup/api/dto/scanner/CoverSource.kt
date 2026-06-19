package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where an [AnalyzedBook]'s cover image came from.
 *
 * Covers come from one of three genuinely different shapes — a file on disk,
 * bytes embedded in an audio file's metadata area, or bytes extracted from
 * embedded metadata and spooled to a scan-temp file to bound scan memory — and
 * the wire shape needs to carry that distinction so consumers know whether to
 * load the `FileEntry` over the file API, render the inline bytes directly, or
 * read back from the spool path.
 *
 * Precedence (high → low): filesystem `cover.*` → first sibling image →
 * embedded artwork from the primary audio file. The Analyzer applies this
 * order; consumers just read the resolved [CoverSource].
 */
@Serializable
sealed interface CoverSource {
    /** Cover image stored as a file alongside the book's audio. */
    @Serializable
    @SerialName("CoverSource.Filesystem")
    data class Filesystem(
        val file: FileEntry,
    ) : CoverSource

    /** Cover artwork extracted from the primary audio file's metadata area. */
    @Serializable
    @SerialName("CoverSource.Embedded")
    data class Embedded(
        val artwork: EmbeddedArtwork,
    ) : CoverSource

    /**
     * Cover artwork extracted from the audio file and spooled to a scan-temp file to bound scan
     * memory — the bytes live on disk under `$LISTENUP_HOME/scan-spool/<scanId>/`, not in heap.
     * Read back by `BookPersister` at persist time, then the scan's spool dir is cleared.
     */
    @Serializable
    @SerialName("CoverSource.Spooled")
    data class Spooled(
        val path: String,
        val mime: String,
    ) : CoverSource
}
