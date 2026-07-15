package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level search result envelope returned from
 * [com.calypsan.listenup.api.SearchService.search].
 *
 * Contains hits across all four searchable domains. Each list is independently
 * capped at the caller's `limit` parameter (default 20, max 100). An empty list
 * for a category means no matches were found — it is never a failure.
 *
 * Books matched by embedded tag names (via the `book_search.tags` FTS5 column)
 * appear in the [books] list — no separate query path for that case.
 */
@Serializable
@SerialName("SearchResults")
data class SearchResults(
    /** Books matching the search query, ranked by FTS5 relevance. */
    val books: List<BookHit>,
    /** Contributors matching the search query, ranked by FTS5 relevance. */
    val contributors: List<ContributorHit>,
    /** Series matching the search query, ranked by FTS5 relevance. */
    val series: List<SeriesHit>,
    /** Tags matching the search query, ranked by FTS5 relevance. */
    val tags: List<TagHit>,
    /** Aggregate facet counts over the matched book set. */
    val facets: SearchFacets = SearchFacets(),
)

/**
 * A single book match within [SearchResults].
 *
 * [coverPath] and [coverHash] are populated when cover enrichment has run;
 * `null` values indicate no cover is available yet.
 */
@Serializable
@SerialName("BookHit")
data class BookHit(
    /** Internal book identifier. */
    val id: BookId,
    /** Book title. */
    val title: String,
    /** Display names of all authors credited on this book. */
    val authorNames: List<String>,
    /** Server-relative path to the cover image, or `null` when unavailable. */
    val coverPath: String?,
    /** Content hash of the cover image for cache-busting, or `null` when unavailable. */
    val coverHash: String?,
    /**
     * The primary matched display field with matched query tokens wrapped in the
     * highlight marker U+0002 (STX) … U+0003 (ETX) sentinels the client splits on;
     * `null` when no token matched the displayed text.
     */
    val highlight: String? = null,
)

/**
 * A single contributor match within [SearchResults].
 *
 * [photoPath] is populated when contributor metadata enrichment has run;
 * a `null` value indicates no photo is available yet. [bookCount] reflects
 * the number of books the contributor is credited on at search time.
 */
@Serializable
@SerialName("ContributorHit")
data class ContributorHit(
    /** Internal contributor identifier. */
    val id: ContributorId,
    /** Display name of the contributor, e.g. "Brandon Sanderson". */
    val name: String,
    /**
     * Sort key for the contributor name, e.g. "Sanderson, Brandon".
     * `null` when no sort name has been set.
     */
    val sortName: String?,
    /** Server-relative path to the contributor photo, or `null` when unavailable. */
    val photoPath: String?,
    /** Number of books this contributor is credited on in the library. */
    val bookCount: Int,
    /**
     * The primary matched display field with matched query tokens wrapped in the
     * highlight marker U+0002 (STX) … U+0003 (ETX) sentinels the client splits on;
     * `null` when no token matched the displayed text.
     */
    val highlight: String? = null,
)

/**
 * A single series match within [SearchResults].
 *
 * [coverPath] is populated when series metadata enrichment has run; a `null`
 * value indicates no cover is available yet. [bookCount] reflects the number
 * of books in the series at search time.
 */
@Serializable
@SerialName("SeriesHit")
data class SeriesHit(
    /** Internal series identifier. */
    val id: SeriesId,
    /** Display name of the series, e.g. "The Stormlight Archive". */
    val name: String,
    /**
     * Sort key for the series name, e.g. "Stormlight Archive, The".
     * `null` when no sort name has been set.
     */
    val sortName: String?,
    /** Server-relative path to the series cover image, or `null` when unavailable. */
    val coverPath: String?,
    /** Number of books belonging to this series in the library. */
    val bookCount: Int,
    /**
     * The primary matched display field with matched query tokens wrapped in the
     * highlight marker U+0002 (STX) … U+0003 (ETX) sentinels the client splits on;
     * `null` when no token matched the displayed text.
     */
    val highlight: String? = null,
)
