package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the resolved chapter list on an [AnalyzedBook] came from.
 *
 * Diagnostic provenance one level above [ChapterSource] — that one names
 * the parser strategy *inside an audio file*; this one names which
 * scanner-level signal won the precedence tournament.
 *
 * Precedence (highest first):
 *  1. [AbsMetadata] — `metadata.json` carried a non-empty `chapters` array.
 *     Self-hosters who curate chapter titles in ABS expect their edits to
 *     stick across rescans; the sidecar must override.
 *  2. [Embedded] — the parser found chapters inside the primary audio
 *     file ([parserSource] records which extraction path).
 *  3. [SynthesizedFromTracks] — multi-file book with no higher-precedence
 *     source; one chapter per track, derived from per-track durations and
 *     filenames. See `ChapterSynthesis.kt` for the algorithm.
 *  4. [None] — no chapter signal anywhere.
 */
@Serializable
sealed interface BookChapterSource {
    @Serializable
    @SerialName("BookChapterSource.None")
    data object None : BookChapterSource

    /**
     * Chapters were extracted from the primary audio file by the embedded-metadata
     * parser. [parserSource] records which extraction path succeeded (e.g. MP4 chapter
     * atoms, ID3 CHAP frames).
     */
    @Serializable
    @SerialName("BookChapterSource.Embedded")
    data class Embedded(
        val parserSource: ChapterSource,
    ) : BookChapterSource

    @Serializable
    @SerialName("BookChapterSource.AbsMetadata")
    data object AbsMetadata : BookChapterSource

    @Serializable
    @SerialName("BookChapterSource.SynthesizedFromTracks")
    data object SynthesizedFromTracks : BookChapterSource
}
