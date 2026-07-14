package com.calypsan.listenup.server.metadata.audnexus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Audnexus API response types ──────────────────────────────────────────────
//
// These `@Serializable` types mirror the api.audnex.us JSON wire format. They are
// server-internal — the raw Audnexus shape never crosses the RPC wire (the contract
// carries its own projections in `MetadataDtos`). `@Serializable` also lets
// `AudnexusProvider` cache these values as JSON in `MetadataCacheRepository`.
//
// Every field is defaulted so a sparse Audnexus response (fields vary by region and
// catalog completeness) deserializes cleanly under the lenient server JSON.

/** Full book metadata from `GET /books/{asin}`. */
@Serializable
@SerialName("AudnexusBook")
internal data class AudnexusBook(
    val asin: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val description: String? = null,
    val publisherName: String? = null,
    val releaseDate: String? = null,
    val language: String? = null,
    val formatType: String? = null,
    /** Cover image URL, when the catalog has one. */
    val image: String? = null,
    /** Primary series placement, when the book belongs to one. */
    val seriesPrimary: AudnexusSeries? = null,
    /** Secondary series placement (a publisher collection), when present. */
    val seriesSecondary: AudnexusSeries? = null,
    val genres: List<AudnexusGenre> = emptyList(),
    val authors: List<AudnexusAuthor> = emptyList(),
    val narrators: List<AudnexusNarrator> = emptyList(),
)

/** A series placement inside an [AudnexusBook]. */
@Serializable
@SerialName("AudnexusSeries")
internal data class AudnexusSeries(
    val asin: String? = null,
    val name: String = "",
    /** Position string, verbatim (`"1"`, `"1.5"`). */
    val position: String? = null,
)

/** A genre-family term inside an [AudnexusBook]; [type] is `"genre"` or `"tag"`. */
@Serializable
@SerialName("AudnexusGenre")
internal data class AudnexusGenre(
    val asin: String? = null,
    val name: String = "",
    val type: String = "",
)

/** An author credit (and the `/authors?name=` search-hit shape) — carries an ASIN. */
@Serializable
@SerialName("AudnexusAuthor")
internal data class AudnexusAuthor(
    val asin: String? = null,
    val name: String = "",
)

/** A narrator credit — name-only; Audnexus exposes no narrator ASIN. */
@Serializable
@SerialName("AudnexusNarrator")
internal data class AudnexusNarrator(
    val name: String = "",
)

/** Chapter list from `GET /books/{asin}/chapters`. */
@Serializable
@SerialName("AudnexusChapters")
internal data class AudnexusChapters(
    val asin: String = "",
    val brandIntroDurationMs: Long = 0,
    val brandOutroDurationMs: Long = 0,
    /** Whether the markers are catalog-verified rather than heuristic. */
    val isAccurate: Boolean = false,
    val runtimeLengthMs: Long = 0,
    val chapters: List<AudnexusChapter> = emptyList(),
)

/** A single chapter marker inside [AudnexusChapters]. */
@Serializable
@SerialName("AudnexusChapter")
internal data class AudnexusChapter(
    val title: String = "",
    val startOffsetMs: Long = 0,
    val lengthMs: Long = 0,
)

/** Author profile from `GET /authors/{asin}` — name, bio, and photo only. */
@Serializable
@SerialName("AudnexusAuthorProfile")
internal data class AudnexusAuthorProfile(
    val asin: String = "",
    val name: String = "",
    val description: String? = null,
    /** Author photo URL, when the catalog has one. */
    val image: String? = null,
)
