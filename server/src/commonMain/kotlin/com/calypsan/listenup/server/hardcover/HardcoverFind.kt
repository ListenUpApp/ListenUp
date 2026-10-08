package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.editionFormatOf

/** How many of Hardcover's search hits a Find reads in detail. */
private const val MAX_FIND_SEARCH_BOOKS = 10
private const val MS_PER_SECOND = 1_000L

/**
 * Hardcover in Find (matching redesign PR 2), behind [HardcoverMetadataSource]'s `BookFindSource`. The spec
 * reverses #1542's "a gap filler never identifies a book" for Find alone: here Hardcover's books are
 * candidates, while in composition it still only fills gaps.
 *
 * At most two calls per Find (the spec's Risks table): a search, then one batched read of the linked and
 * found books plus the editions with the book's ASIN and ISBN. A link is the book's Hardcover ref, else
 * anyone's link (a hand-picked one first); with a link, the ASIN and ISBN aren't asked. Every call reads
 * through [catalogToken] and the shared [rateLimiter].
 */
internal class HardcoverFind(
    private val graphQl: HardcoverGraphQlClient,
    private val catalogToken: HardcoverCatalogToken,
    private val rateLimiter: HardcoverRateLimiter,
    private val links: HardcoverBookLinkStore,
    private val sourceSettings: HardcoverSourceSettings,
) {
    /** Off when an admin switched Hardcover metadata off; not configured when no token can read the catalogue. */
    suspend fun availability(): FindAvailability =
        when {
            !sourceSettings.metadataEnabled() -> FindAvailability.Unavailable(UnavailableReason.DISABLED)
            !catalogToken.isAvailable() -> FindAvailability.Unavailable(UnavailableReason.NOT_CONFIGURED)
            else -> FindAvailability.Available
        }

    suspend fun find(lookup: FindLookup): AppResult<FindAnswer> {
        val steps = mutableSetOf(FindStep.TEXT)
        val linked = if (lookup.identify) linkedBooks(lookup) else emptyList()
        if (linked.isNotEmpty()) steps += FindStep.LINK
        val asin = lookup.asin.takeIf { linked.isEmpty() }?.also { steps += FindStep.ASIN }
        val isbn = lookup.isbn.takeIf { linked.isEmpty() }?.also { steps += FindStep.ISBN }

        val searched =
            when (val answer = read { token -> graphQl.searchBooks(token, lookup.text) }) {
                is HardcoverCall.Ok -> answer.value.map { it.bookId }.take(MAX_FIND_SEARCH_BOOKS)
                else -> return failure(answer)
            }
        val ids = (linked + searched).distinct()
        if (ids.isEmpty() && asin == null && isbn == null) return AppResult.Success(FindAnswer(emptyList(), steps))
        val found =
            when (
                val answer =
                    read { token -> graphQl.findBooks(accessToken = token, ids = ids, asin = asin, isbn = isbn) }
            ) {
                is HardcoverCall.Ok -> answer.value
                else -> return failure(answer)
            }
        return AppResult.Success(FindAnswer(found.toFoundBooks(order = ids, linked = linked.toSet()), steps))
    }

    /** The book's Hardcover refs, else anyone's link for it. */
    private suspend fun linkedBooks(lookup: FindLookup): List<Long> =
        lookup.keys.mapNotNull { it.id.toLongOrNull() }.ifEmpty { listOfNotNull(links.catalogBookFor(lookup.bookId)) }

    private suspend fun <T> read(call: suspend (String) -> HardcoverCall<T>): HardcoverCall<T>? =
        catalogToken.read { token ->
            rateLimiter.await()
            call(token)
        }

    /** A failed call: throttling keeps its retry-after; no token at all, or anything else, is Hardcover unavailable. */
    private fun failure(answer: HardcoverCall<*>?): AppResult.Failure =
        when (answer) {
            is HardcoverCall.Throttled -> {
                val seconds = answer.retryAfterMs?.let { (it + MS_PER_SECOND - 1) / MS_PER_SECOND }
                AppResult.Failure(MetadataError.ExternalRateLimited(retryAfterSeconds = seconds))
            }

            null -> {
                AppResult.Failure(
                    HardcoverError.Unavailable(debugInfo = "hardcover find: no token can read the catalogue"),
                )
            }

            is HardcoverCall.Ok, HardcoverCall.Unauthorized, is HardcoverCall.MissingScope, is HardcoverCall.Failed -> {
                AppResult.Failure(HardcoverError.Unavailable(debugInfo = "hardcover find: ${answer.describe()}"))
            }
        }

    private fun HardcoverCall<*>.describe(): String =
        when (this) {
            is HardcoverCall.Ok -> "ok"
            HardcoverCall.Unauthorized -> "token rejected"
            is HardcoverCall.MissingScope -> "missing scope ${scope.orEmpty()}"
            is HardcoverCall.Throttled -> "throttled"
            is HardcoverCall.Failed -> detail
        }
}

/**
 * One [FoundBook] per Hardcover book: the linked books first, then those found by ASIN or ISBN, then the
 * search's own order. Length, narrators and identifiers come from the ASIN's edition, else the ISBN's edition
 * when it is an audiobook, else the book's default audiobook edition.
 */
private fun HardcoverFindResult.toFoundBooks(
    order: List<Long>,
    linked: Set<Long>,
): List<FoundBook> {
    val byId = (books + listOfNotNull(byAsin?.book, byIsbn?.book)).associateBy { it.id }
    val identified = listOfNotNull(byAsin?.run { book.id }, byIsbn?.run { book.id })
    val ids = (order.filter { it in linked } + identified + order).distinct()
    return ids.mapNotNull { id ->
        val book = byId[id] ?: return@mapNotNull null
        val edition =
            byAsin?.takeIf { it.book.id == id }?.edition
                ?: byIsbn?.takeIf { it.book.id == id && it.edition.isAudiobook }?.edition
                ?: book.audioEdition
        FoundBook(
            key = id.toString(),
            title = book.title,
            subtitle = book.subtitle,
            authors = book.authors,
            narrators = edition?.narrators.orEmpty(),
            durationMs = edition?.audioSeconds?.let { it * MS_PER_SECOND },
            releaseDate = edition?.releaseDate ?: book.releaseYear?.toString(),
            format = editionFormatOf(edition?.editionFormat, book.title, book.subtitle),
            asin = edition?.asin,
            isbn = edition?.isbn,
            coverUrl = edition?.imageUrl ?: book.imageUrl,
            viaLink = id in linked,
        )
    }
}
