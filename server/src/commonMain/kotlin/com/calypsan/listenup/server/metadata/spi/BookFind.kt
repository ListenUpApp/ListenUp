package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult

/** Whether a Find source's hits stand as candidates, or only join candidates other sources found. */
enum class FindRole {
    /** Its hits are candidates in their own right (Audible, Hardcover). Routed through `BOOK_CORE`. */
    IDENTIFIES,

    /** Its hits only join a candidate another source found — a cover catalogue (iTunes). Routed through `COVER`. */
    ATTACHES,
}

/** Whether a Find source can take part right now. */
sealed interface FindAvailability {
    /** It can be asked. */
    data object Available : FindAvailability

    /** It can't, for [reason] — a standing condition, not a failure. */
    data class Unavailable(
        val reason: UnavailableReason,
    ) : FindAvailability
}

/** One of a book's keys at one source: the source's own [id], and the store it was found in when it has stores. */
data class FindKey(
    val id: String,
    val region: String? = null,
)

/**
 * Everything Find knows about a book, handed to one source.
 *
 * [keys] are this source's own refs for the book. [identify] is false under "Search by title": the source
 * then skips links (its own fallbacks too) and identifiers. [asin] and [isbn] are the book's own identifiers,
 * null under "Search by title". [text] is the search query — title and author, or what the person typed;
 * [title] and [primaryAuthor] are the book's own, for sources that search by fields.
 */
data class FindLookup(
    val bookId: String,
    val identify: Boolean,
    val keys: List<FindKey>,
    val asin: String?,
    val isbn: String?,
    val text: String,
    val title: String,
    val primaryAuthor: String?,
)

/** Which of Find's steps a source actually ran. */
enum class FindStep {
    /** It resolved an existing link. */
    LINK,

    /** It looked the book up by ASIN. */
    ASIN,

    /** It looked the book up by ISBN. */
    ISBN,

    /** It searched by text. */
    TEXT,
}

/**
 * One book a source found, with what Find merges and ranks on. Only [key] (the source's own id for it) and
 * [title] are required. [region] is the store it was found in, for a source with stores; [viaLink] marks a hit
 * that came from the book's existing link.
 */
data class FoundBook(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val durationMs: Long? = null,
    val releaseDate: String? = null,
    val format: EditionFormat? = null,
    val asin: String? = null,
    val isbn: String? = null,
    val coverUrl: String? = null,
    val region: String? = null,
    val chapterCount: Int? = null,
    val viaLink: Boolean = false,
)

/** A source's answer to one Find: what it found, and which steps it ran to find it. */
data class FindAnswer(
    val books: List<FoundBook>,
    val steps: Set<FindStep>,
)

/**
 * Find (the matching redesign): look a book up by everything we know in one go — its link at this source,
 * its ASIN or ISBN, then a text search — so each source batches its own calls. The return convention is
 * [MetadataCapability]'s: [AppResult.Failure] when the source errored; nothing found is an empty answer. A
 * rate limit is `MetadataError.ExternalRateLimited`, with its retry-after when the source gave one.
 */
interface BookFindSource : MetadataCapability {
    /** Whether this source's hits stand as candidates or only attach to them. */
    val findRole: FindRole get() = FindRole.IDENTIFIES

    /** Whether this source can take part right now — checked before every Find, never cached. */
    suspend fun findAvailability(): FindAvailability = FindAvailability.Available

    /** Finds [lookup]'s book in [locale]. */
    suspend fun findBooks(
        lookup: FindLookup,
        locale: MetadataLocale,
    ): AppResult<FindAnswer>
}

/** A source with stores (Audible): Find applies the store region to it and reports "not found in this store". */
interface RegionalSource : MetadataCapability {
    /** Whether [region] is one of this source's stores. */
    fun hasStore(region: String): Boolean
}

private val DRAMATIZED_MARKER =
    Regex("""\b(dramati[sz]ed|dramati[sz]ation|full[\s-]cast|radio drama)\b""", RegexOption.IGNORE_CASE)
private val ABRIDGED_MARKER = Regex("""\babridged\b""", RegexOption.IGNORE_CASE)
private val UNABRIDGED_MARKER = Regex("""\bunabridged\b""", RegexOption.IGNORE_CASE)

/**
 * An edition's format from what a catalogue says: a dramatisation marker in any of [titles] wins, then the
 * [declared] format (`abridged` / `unabridged`), then an abridged or unabridged marker in a title. Anything
 * else is unknown (null).
 */
fun editionFormatOf(
    declared: String?,
    vararg titles: String?,
): EditionFormat? {
    val text = titles.filterNotNull().joinToString(" ")
    return when {
        DRAMATIZED_MARKER.containsMatchIn(text) -> EditionFormat.DRAMATIZED
        declared.equals("abridged", ignoreCase = true) -> EditionFormat.ABRIDGED
        declared.equals("unabridged", ignoreCase = true) -> EditionFormat.UNABRIDGED
        ABRIDGED_MARKER.containsMatchIn(text) -> EditionFormat.ABRIDGED
        UNABRIDGED_MARKER.containsMatchIn(text) -> EditionFormat.UNABRIDGED
        else -> null
    }
}
