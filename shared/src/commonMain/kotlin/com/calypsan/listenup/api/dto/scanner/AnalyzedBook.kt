package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.Serializable

/**
 * The Analyzer's output: a [CandidateBook] enriched with everything we can
 * infer from path components, filename annotations, and `metadata.json`.
 *
 * Phase 2 fills in path-derived fields (title/authors/series/sequence/
 * narrators/asin/year) plus whatever an `metadata.json` overlay supplies.
 * Phase 3 (audiometa Kotlin port) fills in embedded-tag fields without
 * changing the shape — every embedded-only field is already nullable here.
 *
 * `sources` records the [MetadataSource]s that contributed at least one
 * field, so consumers can debug precedence.
 */
@Serializable
data class AnalyzedBook(
    val candidate: CandidateBook,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<SeriesEntry> = emptyList(),
    val publishedYear: Int? = null,
    val asin: String? = null,
    val isbn: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val abridged: Boolean? = null,
    val explicit: Boolean? = null,
    val cover: FileEntry? = null,
    val tracks: List<TrackEntry> = emptyList(),
    val sources: Set<MetadataSource> = emptySet(),
)
