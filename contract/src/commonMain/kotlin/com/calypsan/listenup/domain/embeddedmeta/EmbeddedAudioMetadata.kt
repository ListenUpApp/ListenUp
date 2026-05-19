package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Aggregate of every metadata signal extracted from a single audio file.
 *
 * Produced by `EmbeddedMetadataParser.parse(source)` (server-side); consumed by
 * the Phase 2 Analyzer as the "embedded metadata" input to its precedence-merge
 * stage (ABS invariant #7). Crosses the wire when Phase 4's Books domain
 * returns it on RPC service signatures.
 *
 * For multi-file books, the parser is invoked on the primary audio file
 * (first by stable sort) only; per spec §3 non-goals.
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
)
