package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-format unsupported-file count for [EmbeddedScanCounters.unsupportedFormats].
 *
 * Surfaces operationally meaningful detail in scan summaries — operators see
 * "12 FLAC files awaiting parser support" instead of an opaque "12 unsupported".
 *
 * Only files whose detector recognised a named format land here. Files whose
 * magic bytes weren't recognised at all increment
 * [EmbeddedScanCounters.unrecognisedMagic] instead — they aren't a known
 * format we'll later support, just unidentifiable bytes.
 */
@Serializable
@SerialName("UnsupportedFormatCount")
data class UnsupportedFormatCount(
    val format: AudioFormat,
    val count: Int,
)

/**
 * Aggregated counters describing the embedded-metadata enrichment pass over
 * a single scan's books. Exposed on [ScanResultSummary] so the
 * `ScanEvent.Completed` event carries operational signal without forcing
 * clients to walk the full book list.
 *
 * Computed once per scan in `ScanResult.toSummary()` from each
 * [AnalyzedBook.embeddedStatus] and [AnalyzedBook.embedded] field:
 *
 * - [parsed] — `embeddedStatus = MetadataStatus.Available`. The parser
 *   succeeded and produced a populated [com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata].
 * - [unsupported] — `embeddedStatus = MetadataStatus.UnsupportedFormat`.
 *   Total count regardless of whether the format was named; the
 *   [unsupportedFormats] breakdown gives the per-format split for the
 *   subset whose format **was** named.
 * - [parseErrors] — `embeddedStatus = MetadataStatus.ParseError`. The
 *   parser failed on the file's bytes (corrupt header, truncated stream,
 *   IO error).
 * - [withChapters] / [withArtwork] — counts of books where the parser
 *   succeeded **and** the embedded metadata carried that signal.
 * - [unsupportedFormats] — type-safe per-format breakdown of the
 *   [unsupported] count where the format was named (FLAC, Ogg, Opus).
 *   Implementation: List<[UnsupportedFormatCount]> rather than
 *   `Map<AudioFormat, Int>` because non-string-keyed maps serialise as
 *   awkward `[key, value]` arrays in JSON.
 * - [unrecognisedMagic] — count of [unsupported] entries whose detector
 *   couldn't name the format at all.
 */
@Serializable
@SerialName("EmbeddedScanCounters")
data class EmbeddedScanCounters(
    val parsed: Int = 0,
    val unsupported: Int = 0,
    val parseErrors: Int = 0,
    val withChapters: Int = 0,
    val withArtwork: Int = 0,
    val unsupportedFormats: List<UnsupportedFormatCount> = emptyList(),
    val unrecognisedMagic: Int = 0,
)
