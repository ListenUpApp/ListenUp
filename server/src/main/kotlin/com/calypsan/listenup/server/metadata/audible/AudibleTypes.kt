package com.calypsan.listenup.server.metadata.audible

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

// ─── FlexibleFloat32 ─────────────────────────────────────────────────────────

/**
 * Handles Audible API fields that may arrive as either a JSON number (`4.8`) or a
 * JSON string (`"4.8"`). The rating's `display_average_rating` sub-field is the
 * observed trigger; Go's reference uses a custom `UnmarshalJSON` for the same
 * reason (see `client.go` → `FlexibleFloat32`).
 */
@Serializable(with = FlexibleFloat32Serializer::class)
@JvmInline
value class FlexibleFloat32(
    val value: Float,
)

internal object FlexibleFloat32Serializer : KSerializer<FlexibleFloat32> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleFloat32", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): FlexibleFloat32 {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: error("FlexibleFloat32 only supports JSON decoding")
        val element =
            jsonDecoder.decodeJsonElement() as? JsonPrimitive
                ?: error("FlexibleFloat32 expected a JSON primitive")
        val float =
            element.floatOrNull
                ?: element.contentOrNull?.toFloatOrNull()
                ?: error("FlexibleFloat32 cannot parse: $element")
        return FlexibleFloat32(float)
    }

    override fun serialize(
        encoder: Encoder,
        value: FlexibleFloat32,
    ) {
        encoder.encodeFloat(value.value)
    }
}

// ─── Search params ────────────────────────────────────────────────────────────

/**
 * Parameters for an Audible catalog search. Mirrors Go's `SearchParams`.
 *
 * Any combination of fields may be set; at least one should be non-null for a
 * meaningful response.
 */
data class SearchParams(
    val keywords: String? = null,
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    /** Max results; clamped server-side to 1–50. Default 25 matches Go. */
    val limit: Int = DEFAULT_NUM_RESULTS,
) {
    companion object {
        const val DEFAULT_NUM_RESULTS: Int = 25
        const val MAX_NUM_RESULTS: Int = 50
    }
}

// ─── Raw API response types ───────────────────────────────────────────────────
// These types mirror the Audible JSON wire format exactly. Field names match
// the Go `rawProduct`, `rawContributor`, `rawSeries`, `rawRating`, and
// `rawChapterInfo` structs in `client.go`.

/** A contributor (author or narrator) reference as returned in product listings. */
@Serializable
data class RawContributor(
    val asin: String = "",
    val name: String,
    /** "author", "writer", "narrator", or empty. */
    val role: String = "",
)

/** A series reference as returned in product listings. */
@Serializable
data class RawSeries(
    val asin: String = "",
    val title: String,
    /** Series position, e.g. "1", "2", "1-3". */
    val sequence: String = "",
)

/** A single rung in Audible's category ladder. */
@Serializable
data class RawCategory(
    val id: String,
    val name: String,
)

/** A full ladder (root → leaf) of category nodes. */
@Serializable
data class RawCategoryLadder(
    val ladder: List<RawCategory> = emptyList(),
)

/** The overall_distribution sub-object nested inside the rating block. */
@Serializable
data class RawRatingDistribution(
    @SerialName("display_average_rating")
    val displayAverageRating: FlexibleFloat32 = FlexibleFloat32(0f),
    @SerialName("num_reviews")
    val numReviews: Int = 0,
)

/** Top-level rating object. */
@Serializable
data class RawRating(
    @SerialName("overall_distribution")
    val overallDistribution: RawRatingDistribution = RawRatingDistribution(),
)

/**
 * Raw product JSON as returned by both `GET /1.0/catalog/products` (search) and
 * `GET /1.0/catalog/products/{asin}` (single book). Nullable fields use Audible's
 * omitempty conventions — the response_groups requested determine which appear.
 */
@Serializable
data class RawProduct(
    val asin: String,
    val title: String,
    val subtitle: String = "",
    @SerialName("publisher_name")
    val publisherName: String = "",
    @SerialName("release_date")
    val releaseDate: String = "",
    @SerialName("runtime_length_min")
    val runtimeLengthMin: Int = 0,
    @SerialName("merchandising_summary")
    val merchandisingSummary: String = "",
    @SerialName("product_images")
    val productImages: Map<String, String> = emptyMap(),
    val authors: List<RawContributor> = emptyList(),
    val narrators: List<RawContributor> = emptyList(),
    val series: List<RawSeries> = emptyList(),
    @SerialName("category_ladders")
    val categoryLadders: List<RawCategoryLadder> = emptyList(),
    val language: String = "",
    val rating: RawRating? = null,
)

/** Top-level wrapper for a search response. */
@Serializable
data class RawSearchResponse(
    val products: List<RawProduct> = emptyList(),
)

/** Top-level wrapper for a single-book response. */
@Serializable
data class RawBookResponse(
    val product: RawProduct? = null,
)

// ─── Chapters ─────────────────────────────────────────────────────────────────

/** A single chapter as returned by Audible's content-metadata endpoint. */
@Serializable
data class RawChapter(
    val title: String,
    @SerialName("start_offset_ms")
    val startOffsetMs: Long,
    @SerialName("start_offset_sec")
    val startOffsetSec: Long = 0,
    @SerialName("length_ms")
    val lengthMs: Long,
)

/** The chapter_info block inside content_metadata. */
@Serializable
data class RawChapterInfo(
    val chapters: List<RawChapter> = emptyList(),
)

/** content_metadata envelope returned by `GET /1.0/content/{asin}/metadata`. */
@Serializable
data class RawContentMetadata(
    @SerialName("chapter_info")
    val chapterInfo: RawChapterInfo = RawChapterInfo(),
)

/** Top-level wrapper for a chapters response. */
@Serializable
data class RawChaptersResponse(
    @SerialName("content_metadata")
    val contentMetadata: RawContentMetadata = RawContentMetadata(),
)

// ─── Domain output types ──────────────────────────────────────────────────────
// These are the shapes that AudibleClient returns — clean domain types
// independent of wire format details.
//
// @Serializable is applied here so MetadataService can cache these values as
// JSON in MetadataCacheRepository. This is a server-internal serialization
// concern; these types do NOT cross the RPC wire (MetadataDtos.kt carries
// the contract-level projections).

/** A contributor (author or narrator) after role separation. */
@Serializable
data class AudibleContributor(
    val asin: String,
    val name: String,
)

/** A book's position in a series. */
@Serializable
data class AudibleSeriesEntry(
    val asin: String,
    val name: String,
    /** Position string, e.g. "1", "2", "1-3". */
    val position: String,
)

/** Full audiobook metadata from Audible (single-book lookup). */
@Serializable
data class AudibleBook(
    val asin: String,
    val title: String,
    val subtitle: String,
    val authors: List<AudibleContributor>,
    val narrators: List<AudibleContributor>,
    val publisher: String,
    val releaseDate: String,
    val runtimeMinutes: Int,
    val description: String,
    val coverUrl: String,
    val series: List<AudibleSeriesEntry>,
    val genres: List<String>,
    /**
     * Audible's category ladders preserved root → leaf, one inner list per
     * ladder (e.g. `[["Fiction", "Fantasy", "LitRPG"]]`). [genres] is the flat
     * dedup of the same rungs, kept for back-compat; this field retains the
     * hierarchy so the match-apply path can nest genres. Defaults to empty so
     * pre-existing cached values and test fixtures deserialize unchanged.
     */
    val genreLadders: List<List<String>> = emptyList(),
    val language: String,
    val rating: Float,
    val ratingCount: Int,
)

/** A lighter search-result entry (not all fields populated). */
@Serializable
data class AudibleSearchResult(
    val asin: String,
    val title: String,
    val subtitle: String,
    val authors: List<AudibleContributor>,
    val narrators: List<AudibleContributor>,
    val coverUrl: String,
    val runtimeMinutes: Int,
    val releaseDate: String,
)

/** A chapter marker. */
@Serializable
data class AudibleChapter(
    val title: String,
    val startMs: Long,
    val durationMs: Long,
)

/**
 * Full contributor profile scraped from an Audible author page.
 *
 * Unlike book metadata (which uses the catalog JSON API), contributor data is
 * fetched by scraping `www.audible.{tld}/author/x/{asin}` — Audible's official
 * API no longer returns contributor images or biographies. Ported from Go's
 * `ContributorProfile` in `server/internal/metadata/audible/types.go`.
 *
 * `@Serializable` so [com.calypsan.listenup.server.services.MetadataService]
 * can cache this value as JSON in `MetadataCacheRepository`.
 */
@Serializable
data class AudibleContributorProfile(
    /** Audible contributor ASIN — the stable external key. */
    val asin: String,
    /** Display name extracted from the author page heading. */
    val name: String,
    /** Plain-text biography extracted from the expander block, or empty string. */
    val biography: String,
    /** Author photo URL from the `og:image` meta tag, or empty string if unavailable. */
    val imageUrl: String,
)
