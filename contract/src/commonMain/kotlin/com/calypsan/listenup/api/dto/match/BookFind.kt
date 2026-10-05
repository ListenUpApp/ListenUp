package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.metadata.MetadataLocale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How Find picks its keys. */
@Serializable
enum class FindStrategy {
    /** Strongest first: the book's existing links, then its ASIN or ISBN, then title, author and length. */
    AUTOMATIC,

    /** "Search by title": skip links and identifiers, search by title and author only. */
    TITLE_AUTHOR,
}

/**
 * What a Find asks beyond the book itself. [query] replaces the title-and-author search when the person typed
 * one (results are still ranked against their copy); [regionOverride] picks another store for this search only.
 */
@Serializable
@SerialName("BookFindRequest")
data class BookFindRequest(
    @SerialName("query") val query: String? = null,
    @SerialName("strategy") val strategy: FindStrategy = FindStrategy.AUTOMATIC,
    @SerialName("regionOverride") val regionOverride: MetadataLocale? = null,
)

/** Which of the book's own identifiers Find searched by. */
@Serializable
enum class IdentifierKind {
    /** Audible's identifier. */
    ASIN,

    /** The ISBN. */
    ISBN,
}

/** One step Find took, in order, so clients can say what it started from. */
@Serializable
sealed interface SearchStep {
    /** It started from your existing link at [source]. */
    @Serializable
    @SerialName("SearchStep.ExistingLink")
    data class ExistingLink(
        @SerialName("source") val source: MetadataSource,
    ) : SearchStep

    /** It searched by the book's [kind] identifier. */
    @Serializable
    @SerialName("SearchStep.Identifier")
    data class Identifier(
        @SerialName("kind") val kind: IdentifierKind,
    ) : SearchStep

    /** It searched by title and author, ranking by length. */
    @Serializable
    @SerialName("SearchStep.TitleAuthorLength")
    data object TitleAuthorLength : SearchStep

    /** It searched by what the person typed, [query]. */
    @Serializable
    @SerialName("SearchStep.YourQuery")
    data class YourQuery(
        @SerialName("query") val query: String,
    ) : SearchStep
}

/** The facts of your copy every candidate is ranked against, echoed so the ranking and the strip agree. */
@Serializable
@SerialName("YourCopy")
data class YourCopy(
    @SerialName("durationMs") val durationMs: Long?,
    @SerialName("narrators") val narrators: List<String>,
    @SerialName("chapterCount") val chapterCount: Int?,
    @SerialName("year") val year: Int?,
    @SerialName("format") val format: EditionFormat?,
)

/**
 * A Find's answer: [candidates] ranked against [yourCopy], best first; the [steps] it took; how every
 * source fared; and the store it searched, when a source has stores.
 */
@Serializable
@SerialName("BookFindResult")
data class BookFindResult(
    @SerialName("yourCopy") val yourCopy: YourCopy,
    @SerialName("steps") val steps: List<SearchStep>,
    @SerialName("candidates") val candidates: List<BookCandidate>,
    @SerialName("sources") val sources: List<SourceStatus>,
    @SerialName("region") val region: RegionContext?,
)
