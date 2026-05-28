package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Aggregate counts over the full matched book set (computed before the per-category limit),
 * powering the search filter UI. Genre/author/narrator buckets always describe books.
 */
@Serializable
@SerialName("SearchFacets")
data class SearchFacets(
    /** Hit counts per result type for the current query. */
    @SerialName("types") val types: TypeCounts = TypeCounts(),
    /** Top genres among matched books, keyed by slug. */
    @SerialName("genres") val genres: List<FacetBucket> = emptyList(),
    /** Top authors among matched books, keyed by contributor id. */
    @SerialName("authors") val authors: List<FacetBucket> = emptyList(),
    /** Top narrators among matched books, keyed by contributor id. */
    @SerialName("narrators") val narrators: List<FacetBucket> = emptyList(),
)

/** A single facet bucket: a stable [key], a display [label], and a [count]. */
@Serializable
@SerialName("FacetBucket")
data class FacetBucket(
    @SerialName("key") val key: String,
    @SerialName("label") val label: String,
    @SerialName("count") val count: Int,
)

/** Per-type hit counts for the current query (0 for types not searched in books-only mode). */
@Serializable
@SerialName("TypeCounts")
data class TypeCounts(
    @SerialName("books") val books: Int = 0,
    @SerialName("contributors") val contributors: Int = 0,
    @SerialName("series") val series: Int = 0,
    @SerialName("tags") val tags: Int = 0,
)
