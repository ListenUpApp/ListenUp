package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Aggregate of every metadata signal extracted from a single audio file.
 *
 * Produced by `EmbeddedMetadataParser.parse(source)` (server-side); consumed by
 * the Analyzer as the "embedded metadata" input to its precedence-merge
 * stage. Crosses the wire when the Books domain
 * returns it on RPC service signatures.
 *
 * For multi-file books, the parser is invoked on the primary audio file
 * (first by stable sort) only.
 */
@Serializable
@SerialName("EmbeddedAudioMetadata")
data class EmbeddedAudioMetadata(
    val format: AudioFormat,
    val durationMs: Long,
    val tags: AudioTags,
    val chapters: List<Chapter>,
    val chaptersSource: ChapterSource,
    val artwork: EmbeddedArtwork?,
    /** Technical audio-stream info (codec/profile/spatial/bitrate/rate/channels) for the primary file; null when undetermined. */
    val audioStream: AudioStreamInfo? = null,
)
