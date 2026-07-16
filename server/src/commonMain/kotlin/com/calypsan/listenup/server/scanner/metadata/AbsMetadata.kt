package com.calypsan.listenup.server.scanner.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema for ABS-authored `metadata.json` sidecar files.
 *
 * ABS writes one of these in every book folder when `storeMetadataWithItem`
 * is on (the common self-hoster setting). The schema is flat in current ABS
 * versions; legacy ABS versions wrap fields in a nested `metadata` object —
 * the reader auto-flattens both forms.
 *
 * `series` arrives as an array of `"Name #seq"` strings — this DTO keeps
 * them as raw strings; the reader splits them into
 * [com.calypsan.listenup.api.dto.scanner.SeriesEntry] downstream.
 *
 * Source: ABS `server/scanner/BookScanner.js:810-879` (`saveMetadataFile`)
 * and `abmetadataGenerator.js:9-20` (legacy unwrap).
 */
@Serializable
data class AbsMetadata(
    @SerialName("title")
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val publishedYear: Int? = null,
    val publishedDate: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val language: String? = null,
    val explicit: Boolean? = null,
    val abridged: Boolean? = null,
    val chapters: List<AbsChapter> = emptyList(),
)
