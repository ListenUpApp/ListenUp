package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for a merged metadata match result (multi-provider enrichment, Audible-keyed).
 * This is a subset of the server's internal `AudibleBook` type —
 * the raw Audible response shape is never exposed across the contract boundary.
 *
 * [asin] is the Audible identifier; clients pass it back to refresh or apply
 * metadata. [coverUrl] is Audible's own cover thumbnail; [coverUrlMaxSize] is
 * the high-resolution iTunes variant (up to 7000×7000) when a cover enrichment
 * lookup succeeded, or `null` if iTunes returned nothing.
 */
@Serializable
@SerialName("MetadataBook")
data class MetadataBook(
    /** Audible Standard Identification Number — the stable external key. */
    val asin: String,
    /** Full book title. */
    val title: String,
    /** Optional subtitle (e.g. "Book One of the Stormlight Archive"). */
    val subtitle: String?,
    /** Plain-text description (HTML stripped by the server). */
    val description: String?,
    /** Publisher name, e.g. "Macmillan Audio". */
    val publisher: String?,
    /** Release date string as returned by Audible, e.g. "2010-08-31". */
    val releaseDate: String?,
    /** Audiobook runtime in minutes. */
    val runtimeMinutes: Int?,
    /** BCP-47 language tag, e.g. "en-US". */
    val language: String?,
    /** Authors and writers. */
    val authors: List<MetadataContributorRef>,
    /** Narrators. */
    val narrators: List<MetadataContributorRef>,
    /** Series this book belongs to (may be empty). */
    val series: List<MetadataSeriesRef>,
    /** Genre labels as returned by Audible (may be empty). */
    val genres: List<String>,
    /** Mood labels scraped from Audible's product topic-tags (may be empty). */
    val moods: List<String> = emptyList(),
    /** Trope/theme tag labels scraped from Audible's product topic-tags (may be empty). */
    val tags: List<String> = emptyList(),
    /** Audible cover thumbnail URL (typically 500×500). */
    val coverUrl: String?,
    /** High-resolution cover URL from iTunes (up to 7000×7000), or `null` if unavailable. */
    val coverUrlMaxSize: String?,
    /** Per-field provider provenance for this merged match; null on lean search hits and legacy payloads. */
    val matchProvenance: MatchProvenance? = null,
)

/**
 * Provider provenance for a merged match preview — display labels only, for the wizard.
 *
 * The server merges each field across a configured provider chain; this reports where the merged
 * record's values came from. Values are human display labels ("Audible", "iTunes", "Audnexus", or a
 * custom provider's title-cased name), never stable codes — clients display them, never switch on them.
 */
@Serializable
@SerialName("MatchProvenance")
data class MatchProvenance(
    /** Distinct display labels of every provider that supplied a field, in BookField order. The footer. */
    val contributingSources: List<String> = emptyList(),
    /**
     * Text/list fields whose winner is NOT that field's configured primary provider ("fell through to a
     * fallback"). Keyed by the on-wire [com.calypsan.listenup.api.metadata.BookField]; value is a display
     * label. Sparse — a present entry means "came from a fallback." Excludes COVER (see cover fields below).
     */
    val fallbackFields: Map<com.calypsan.listenup.api.metadata.BookField, String> = emptyMap(),
    /** Display label of the provider supplying the applied (max-size) cover; set whenever a cover exists. */
    val coverSource: String? = null,
    /** Probed pixel width/height of the applied cover, or null when the probe found nothing. */
    val coverWidth: Int? = null,
    val coverHeight: Int? = null,
)

/**
 * A contributor (author or narrator) reference within a [MetadataBook].
 *
 * [asin] is the Audible contributor identifier used to look up a full
 * [MetadataContributorProfile]; it may be absent when Audible omits it.
 */
@Serializable
@SerialName("MetadataContributorRef")
data class MetadataContributorRef(
    /** Audible contributor ASIN, or `null` when Audible omits it. */
    val asin: String?,
    /** Display name, e.g. "Brandon Sanderson". */
    val name: String,
)

/**
 * A series reference within a [MetadataBook].
 *
 * [sequence] is the position string from Audible — may be an integer ("1"),
 * a decimal ("1.5"), or a range ("1-3").
 */
@Serializable
@SerialName("MetadataSeriesRef")
data class MetadataSeriesRef(
    /** Audible series ASIN, or `null` when Audible omits it. */
    val asin: String?,
    /** Series title, e.g. "The Stormlight Archive". */
    val title: String,
    /** Position in the series, or `null` if Audible does not provide it. */
    val sequence: String?,
)

/**
 * A paginated or flat list of [MetadataBook] search hits.
 *
 * Wrapping in a container type (rather than returning `List<MetadataBook>`
 * directly) keeps the wire shape extensible — pagination cursors, facet counts,
 * or total-hit metadata can be added in a later revision without a breaking
 * contract change.
 */
@Serializable
@SerialName("MetadataSearchResults")
data class MetadataSearchResults(
    /** Matched books in Audible relevance order. */
    val hits: List<MetadataBook>,
)

/**
 * The full chapter list for an audiobook, as returned by Audible's
 * content-metadata endpoint.
 *
 * Each [MetadataChapter] carries an absolute start offset and duration so
 * clients can display the chapter list and seek to any chapter without
 * additional computation.
 */
@Serializable
@SerialName("MetadataChapters")
data class MetadataChapters(
    /** Chapters in playback order. */
    val chapters: List<MetadataChapter>,
)

/**
 * A single chapter marker within [MetadataChapters].
 *
 * [startMs] and [lengthMs] are absolute millisecond offsets from the start of
 * the audiobook — they match Audible's `start_offset_ms` / `length_ms` fields.
 */
@Serializable
@SerialName("MetadataChapter")
data class MetadataChapter(
    /** Chapter display title, e.g. "Chapter 1: The Way of Kings". */
    val title: String,
    /** Absolute start offset from the beginning of the audiobook, in milliseconds. */
    val startMs: Long,
    /** Chapter duration in milliseconds. */
    val lengthMs: Long,
)

/**
 * Full profile for an Audible contributor (author or narrator).
 *
 * Fetched via [com.calypsan.listenup.api.MetadataLookupService.getContributorMetadata].
 * All fields except [asin] and [name] are optional because Audible's contributor
 * API returns sparse data depending on the contributor's profile completeness.
 */
@Serializable
@SerialName("MetadataContributorProfile")
data class MetadataContributorProfile(
    /** Audible contributor ASIN — the stable external key. */
    val asin: String,
    /** Display name, e.g. "Brandon Sanderson". */
    val name: String,
    /** Sort name for alphabetical ordering, e.g. "Sanderson, Brandon". */
    val sortName: String?,
    /** Plain-text biography. */
    val description: String?,
    /** Profile image URL. */
    val imageUrl: String?,
    /** Birth date string as returned by Audible, or `null`. */
    val birthDate: String?,
    /** Death date string as returned by Audible, or `null`. */
    val deathDate: String?,
    /** Official website URL, or `null`. */
    val website: String?,
)

/**
 * A lightweight contributor search hit returned by
 * [com.calypsan.listenup.api.MetadataLookupService.searchContributorMetadata].
 *
 * Clients use the [asin] to load the full [MetadataContributorProfile] on
 * demand; the [name] is sufficient for a pick-list or auto-suggest UI.
 */
@Serializable
@SerialName("MetadataContributorHit")
data class MetadataContributorHit(
    /** Audible contributor ASIN. */
    val asin: String,
    /** Display name, e.g. "Patrick Rothfuss". */
    val name: String,
)

/** Which catalog a [CoverOption] came from. */
@Serializable
@SerialName("CoverOptionSource")
enum class CoverOptionSource {
    @SerialName("AUDIBLE")
    AUDIBLE,

    @SerialName("ITUNES")
    ITUNES,

    /**
     * Any other provider — Audnexus, or an operator-declared custom source. The picker groups these
     * as generic candidates so a cover from a provider without a first-class label still surfaces,
     * rather than being silently dropped.
     */
    @SerialName("OTHER")
    OTHER,
}

/**
 * One cover-art candidate for a book, returned by `searchCovers`. [url] is the full
 * (max-resolution where available) image URL the client passes back to `applyCover`.
 * [width]/[height] are pixels, or 0 when the dimension probe could not determine them.
 * [sourceId] is provenance — the Audible ASIN or iTunes collectionId.
 */
@Serializable
@SerialName("CoverOption")
data class CoverOption(
    @SerialName("source") val source: CoverOptionSource,
    @SerialName("url") val url: String,
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
    @SerialName("sourceId") val sourceId: String,
)

/** The cover candidates for a book, Audible first then iTunes. */
@Serializable
@SerialName("CoverSearchResults")
data class CoverSearchResults(
    @SerialName("options") val options: List<CoverOption>,
)

/**
 * A user's per-field choices for applying an Audible match to a book, sent with
 * [com.calypsan.listenup.api.MetadataLookupService.applyBookMetadata].
 *
 * Each boolean toggles whether the matched value overwrites the book's current value
 * for that scalar field (deselected = leave untouched). The ASIN sets choose which of
 * the match's contributors/series to apply: a non-empty set REPLACES the book's entries
 * for that role/relation with the selected ones; an empty set leaves them untouched, so
 * the wizard never silently wipes a role the user didn't engage. Contributors/series
 * whose Audible ASIN is absent are not selectable.
 *
 * [genres] carries the raw Audible genre labels selected by the user; the server resolves
 * them through the same 3-step cascade used by the scanner (alias → [GenreNormalizer] →
 * pending). An empty set leaves the book's genres untouched.
 *
 * [moods] and [tags] carry the raw Audible mood / trope labels selected by the user; the
 * server writes the selected values additively through the add-only mood/tag junction writers.
 * An empty set writes nothing for that dimension.
 */
@Serializable
@SerialName("MetadataApplySelection")
data class MetadataApplySelection(
    @SerialName("title") val title: Boolean,
    @SerialName("subtitle") val subtitle: Boolean,
    @SerialName("description") val description: Boolean,
    @SerialName("publisher") val publisher: Boolean,
    @SerialName("releaseDate") val releaseDate: Boolean,
    @SerialName("language") val language: Boolean,
    @SerialName("cover") val cover: Boolean,
    @SerialName("authorAsins") val authorAsins: Set<String>,
    @SerialName("narratorAsins") val narratorAsins: Set<String>,
    @SerialName("seriesAsins") val seriesAsins: Set<String>,
    /** The user's chosen cover URL when [cover] is true; null = the match's default-max cover. */
    @SerialName("coverUrl") val coverUrl: String? = null,
    /** Selected raw genre labels from the match; resolved server-side through the genre cascade. Empty = leave genres untouched. */
    @SerialName("genres") val genres: Set<String> = emptySet(),
    /** Selected raw mood labels from the match; written additively server-side. Empty = write no moods. */
    @SerialName("moods") val moods: Set<String> = emptySet(),
    /** Selected raw trope/tag labels from the match; written additively server-side. Empty = write no tags. */
    @SerialName("tags") val tags: Set<String> = emptySet(),
)
