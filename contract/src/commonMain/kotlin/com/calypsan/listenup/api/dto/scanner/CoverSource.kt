package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where an [AnalyzedBook]'s cover image came from.
 *
 * Covers come from one of two genuinely different shapes — a file on disk
 * or bytes embedded in an audio file's metadata area — and the wire shape
 * needs to carry that distinction so consumers know whether to load the
 * `FileEntry` over the file API or render the inline bytes directly.
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
}
