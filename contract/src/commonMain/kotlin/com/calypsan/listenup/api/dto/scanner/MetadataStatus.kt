package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Outcome of attempting to parse embedded metadata for the book's primary
 * audio file. One status per [AnalyzedBook] — we only parse the primary
 * file (per `EmbeddedAudioMetadata` design), so per-file fidelity would
 * exceed the parsing model.
 *
 * Surfaces in scan summaries so operators see "12 FLAC files detected,
 * parser not available" instead of an opaque per-file error. The
 * scan-summary aggregator groups books by status.
 *
 * `null` on [AnalyzedBook] means the parser was never invoked (e.g. no
 * audio file in the candidate at all). [Available] means the parser
 * succeeded; [UnsupportedFormat] means the detector recognised the format
 * but no parser is registered for it (FLAC/Ogg/Opus today); [ParseError]
 * means the parser failed on the file's bytes.
 */
@Serializable
sealed interface MetadataStatus {
    /** Parser succeeded and produced an `EmbeddedAudioMetadata`. */
    @Serializable
    @SerialName("MetadataStatus.Available")
    data object Available : MetadataStatus

    /**
     * No parser registered for the detected format. The file is **not**
     * dropped from the scan — this is enrichment deferral, not failure.
     * [format] is null when the detector didn't recognise the magic bytes
     * at all; populated when the format is named but unsupported.
     */
    @Serializable
    @SerialName("MetadataStatus.UnsupportedFormat")
    data class UnsupportedFormat(
        val format: AudioFormat? = null,
    ) : MetadataStatus

    /** Parser failed on the file's bytes. The typed [error] carries detail. */
    @Serializable
    @SerialName("MetadataStatus.ParseError")
    data class ParseError(
        val error: AudioMetadataError,
    ) : MetadataStatus
}
