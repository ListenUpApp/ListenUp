package com.calypsan.listenup.server.goodreads

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MatchScorer
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.RatingSource

/**
 * Why a Goodreads lookup failed. Server-internal: each maps onto a [MetadataError] via [toAppError],
 * whose `debugInfo` says which, and whose `message` is the admin's health text.
 */
sealed interface GoodreadsFailure {
    /** The page came back without a readable rating: Goodreads changed its page, so nothing is guessed. */
    data object PageFormatChanged : GoodreadsFailure

    /** Goodreads declined the request with [status] (403, 429 or 503). */
    data class Refused(
        val status: Int,
    ) : GoodreadsFailure

    /** Goodreads could not be reached ([detail] says how). */
    data class Unreachable(
        val detail: String,
    ) : GoodreadsFailure
}

/**
 * How Goodreads' readers rate a book, read from its public book page. Lookup order: the ISBN
 * (`/book/isbn/{isbn}`), then a title-and-author search, where a result counts only when
 * [MatchScorer.isConfidentRatingMatch] accepts it: a wrong score is worse than none. Of several
 * confident results, the closest ([MatchScorer.score]) wins, Goodreads' own order breaking ties, so the
 * book itself outranks a same-author adaptation whose title merely contains it.
 *
 * Goodreads is scraped rather than asked through an API, so it fails on its own terms: a page without
 * a readable rating is [GoodreadsFailure.PageFormatChanged] ([MetadataError.Malformed]) and a refused
 * request is [GoodreadsFailure.Refused] ([MetadataError.ExternalUnavailable]). Both count toward the
 * source's automatic pause. Regionless: Goodreads has one catalog, so the rating carries no region.
 */
class GoodreadsRatingSource(
    private val client: GoodreadsClient,
    private val rateLimiter: GoodreadsRateLimiter,
) : RatingSource {
    override val id: MetadataProviderId = MetadataProviderId.GOODREADS
    override val ratingSource: ExternalRatingSource = ExternalRatingSource.GOODREADS

    override suspend fun getRating(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ExternalRatingMeta?> {
        book.isbn?.let { isbn ->
            rateLimiter.await()
            when (val page = client.bookPageByIsbn(isbn)) {
                GoodreadsFetch.NotFound -> Unit
                else -> return ratingOn(page)
            }
        }
        return ratingByTitle(book)
    }

    private suspend fun ratingByTitle(book: BookIdentity): AppResult<ExternalRatingMeta?> {
        // A title alone never counts as a match, so without an author there is nothing to ask.
        val author = book.primaryAuthor ?: return AppResult.Success(null)
        rateLimiter.await()
        val results =
            when (val page = client.search("${book.title} $author")) {
                is GoodreadsFetch.Page -> GoodreadsPages.searchResults(page.html)
                GoodreadsFetch.NotFound -> emptyList()
                is GoodreadsFetch.Refused -> return failure(GoodreadsFailure.Refused(page.status))
                is GoodreadsFetch.Unreachable -> return failure(GoodreadsFailure.Unreachable(page.detail))
            }
        val best =
            results
                .map { BookMatch(title = it.title, author = it.author, score = 0.0) to it.bookPath }
                .filter { (match, _) -> MatchScorer.isConfidentRatingMatch(book, match) }
                .maxByOrNull { (match, _) -> MatchScorer.score(book, match) }
                ?: return AppResult.Success(null)
        rateLimiter.await()
        return ratingOn(client.bookPage(best.second))
    }

    /** The rating on a fetched book page; a page Goodreads no longer has is a confident "no rating". */
    private fun ratingOn(page: GoodreadsFetch): AppResult<ExternalRatingMeta?> =
        when (page) {
            is GoodreadsFetch.Page -> {
                GoodreadsPages.aggregateRating(page.html)?.let {
                    AppResult.Success(ExternalRatingMeta(average = it.average, count = it.count))
                } ?: failure(GoodreadsFailure.PageFormatChanged)
            }

            GoodreadsFetch.NotFound -> {
                AppResult.Success(null)
            }

            is GoodreadsFetch.Refused -> {
                failure(GoodreadsFailure.Refused(page.status))
            }

            is GoodreadsFetch.Unreachable -> {
                failure(GoodreadsFailure.Unreachable(page.detail))
            }
        }

    private fun failure(reason: GoodreadsFailure): AppResult.Failure = AppResult.Failure(reason.toAppError())
}

/** The typed [MetadataError] a [GoodreadsFailure] crosses the wire as, its `debugInfo` naming which. */
internal fun GoodreadsFailure.toAppError(): AppError =
    when (this) {
        GoodreadsFailure.PageFormatChanged -> {
            MetadataError.Malformed(debugInfo = "goodreads rating: page format changed (no JSON-LD aggregateRating)")
        }

        is GoodreadsFailure.Refused -> {
            MetadataError.ExternalUnavailable(debugInfo = "goodreads rating: the site refused the request ($status)")
        }

        is GoodreadsFailure.Unreachable -> {
            MetadataError.ExternalUnavailable(debugInfo = "goodreads rating: unreachable: $detail")
        }
    }
