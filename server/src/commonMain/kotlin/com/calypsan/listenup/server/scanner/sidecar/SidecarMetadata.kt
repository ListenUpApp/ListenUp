package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.api.dto.scanner.SeriesEntry

/**
 * Metadata extracted from a sidecar file. Every field is optional — a parser
 * fills only what its file format carries. The
 * [com.calypsan.listenup.server.scanner.pipeline.Analyzer] merges this between
 * the embedded-tags tier and the filename tier.
 */
internal data class SidecarMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val publishYear: Int? = null,
    val publisher: String? = null,
    val language: String? = null,
    /** Series entries extracted from a sidecar (e.g. Calibre `<meta name="calibre:series">`). */
    val series: List<SeriesEntry> = emptyList(),
    /** Genre/subject strings extracted from a sidecar (NFO `<genre>`, OPF `<dc:subject>`). */
    val genres: List<String> = emptyList(),
    /** ISBN extracted from a sidecar identifier (OPF `<dc:identifier opf:scheme="ISBN">`). */
    val isbn: String? = null,
    /** Amazon/Audible ASIN extracted from a sidecar identifier (OPF `<dc:identifier opf:scheme="ASIN">`). */
    val asin: String? = null,
    val contributors: List<SidecarContributor> = emptyList(),
)

/** A contributor credited by a sidecar — name plus role (`"author"`, `"narrator"`, …). */
internal data class SidecarContributor(
    val name: String,
    val role: String,
)
