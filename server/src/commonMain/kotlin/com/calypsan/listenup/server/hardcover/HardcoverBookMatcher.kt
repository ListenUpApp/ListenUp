package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.MatchScorer

/** Where a ListenUp book was found on Hardcover. [hcEditionId] is null when Hardcover names no edition to shelve. */
data class HardcoverMatch(
    val hcBookId: Long,
    val hcEditionId: Long?,
    val method: HardcoverMatchMethod,
)

/** What the matcher knows about a ListenUp book, or null when the book is gone. */
fun interface HardcoverBookIdentities {
    /** [bookId]'s identifiers, title and primary author. */
    suspend fun identityOf(bookId: String): BookIdentity?
}

/**
 * Matches a ListenUp book to a Hardcover book (spec B4), stopping at the first confident hit:
 * 1. the ASIN, against `editions.asin` — the exact audiobook edition, kept as found (a book can carry
 *    several audiobook editions, so the ASIN's own edition beats the book's default one);
 * 2. the ISBN, against ISBN-13 and ISBN-10 — the book, preferring its default audiobook edition when
 *    the ISBN's edition isn't one (only reading format "Listened" is; "Both" is not);
 * 3. the title — candidates must agree on title and on at least one author, by the same rule ratings
 *    use ([MatchScorer.isConfidentRatingMatch]); of several, only a [clearWinner] is accepted.
 *
 * `Ok(null)` means "no confident match" (the book becomes NEEDS_MATCH): it never guesses. A failure
 * is returned as a failure, so an outage never masquerades as NEEDS_MATCH. Shares the catalog
 * lookups with [HardcoverRatingSource] but not its stopping rule: ratings skip unrated editions and
 * take the most-rated of several title hits; a reading log must not.
 */
class HardcoverBookMatcher(
    private val graphQl: HardcoverGraphQlClient,
    private val rateLimiter: HardcoverRateLimiter,
) {
    /** [book]'s Hardcover match through [accessToken], or `Ok(null)` when none is confident. */
    suspend fun match(
        accessToken: String,
        book: BookIdentity,
    ): HardcoverCall<HardcoverMatch?> {
        val asin = book.asin
        if (asin != null) {
            rateLimiter.await()
            val hit = graphQl.editionByAsin(accessToken, asin).valueOr { return it }
            if (hit != null) {
                return HardcoverCall.Ok(HardcoverMatch(hit.book.id, hit.editionId, HardcoverMatchMethod.ASIN))
            }
        }
        val isbn = book.isbn
        if (isbn != null) {
            rateLimiter.await()
            val hit = graphQl.editionByIsbn(accessToken, isbn).valueOr { return it }
            if (hit != null) {
                val edition = if (hit.isAudiobook) hit.editionId else hit.book.defaultAudioEditionId ?: hit.editionId
                return HardcoverCall.Ok(HardcoverMatch(hit.book.id, edition, HardcoverMatchMethod.ISBN))
            }
        }
        if (book.primaryAuthor == null) return HardcoverCall.Ok(null)
        rateLimiter.await()
        val confident =
            graphQl
                .booksTitled(accessToken, book.title)
                .valueOr {
                    return it
                }.filter { it.isConfidentMatchFor(book) }
        return HardcoverCall.Ok(
            confident.clearWinner()?.let { winner ->
                HardcoverMatch(
                    winner.id,
                    winner.defaultAudioEditionId,
                    HardcoverMatchMethod.SEARCH,
                )
            },
        )
    }
}

/** Title and at least one credited author agree, by the rule ratings use. An author-less record never does. */
internal fun HardcoverCatalogBook.isConfidentMatchFor(book: BookIdentity): Boolean =
    authors.any { author ->
        MatchScorer.isConfidentRatingMatch(book, BookMatch(title = title, author = author, score = 0.0))
    }

private const val WINNER_READER_RATIO = 20
private const val WINNER_MIN_READERS = 50

/**
 * The one candidate Hardcover's duplicate records leave no doubt about, or null. A lone candidate
 * wins; of several, the only one with an audiobook edition wins, else the most-read one if it has at
 * least [WINNER_MIN_READERS] readers and [WINNER_READER_RATIO]× the runner-up's. Anything closer is
 * a genuine tie between comparable editions, and the matcher declines rather than guess.
 */
internal fun List<HardcoverCatalogBook>.clearWinner(): HardcoverCatalogBook? {
    if (size < 2) return singleOrNull()
    singleOrNull { it.defaultAudioEditionId != null }?.let { return it }
    val (top, runnerUp) = sortedByDescending { it.readers }
    return top.takeIf { it.readers >= WINNER_MIN_READERS && it.readers >= runnerUp.readers * WINNER_READER_RATIO }
}
