package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MatchScorer
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.metadata.spi.RatingSourceAvailability

/**
 * How Hardcover's readers rate a book, fetched through a connected account (Hardcover's API takes
 * only a user's token). Lookup order: the Audible ASIN, then the ISBN, then the title, where a title
 * candidate counts only when [MatchScorer.isConfidentRatingMatch] accepts its author: a wrong score
 * is worse than none. Of several confident title matches, the most-rated one wins, since Hardcover
 * often holds duplicate records of one book and the busiest carries the readers.
 *
 * Unavailable without a client id ([clientConfigured]) or a healthy connection; both are standing
 * conditions the fetcher skips without penalty. A broken connection or rejected token is a
 * [HardcoverError.ConnectionBroken] failure (the admin must reconnect); an unreachable Hardcover is
 * [HardcoverError.Unavailable]. Both count toward the source's automatic pause.
 */
class HardcoverRatingSource(
    private val graphQl: HardcoverGraphQlClient,
    private val connection: HardcoverRatingConnection,
    private val rateLimiter: HardcoverRateLimiter,
    private val clientConfigured: Boolean,
) : RatingSource {
    override val id: MetadataProviderId = MetadataProviderId.HARDCOVER
    override val ratingSource: ExternalRatingSource = ExternalRatingSource.HARDCOVER

    override suspend fun availability(): RatingSourceAvailability =
        when {
            !clientConfigured -> RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NOT_CONFIGURED)
            connection.pick() == null -> RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NO_CONNECTION)
            else -> RatingSourceAvailability.Available
        }

    override suspend fun getRating(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ExternalRatingMeta?> {
        val token =
            when (val lookup = connection.token()) {
                is TokenLookup.Valid -> lookup.accessToken
                TokenLookup.NotConnected -> return AppResult.Success(null)
                is TokenLookup.Broken -> return brokenConnection("connection broken: ${lookup.reason}")
                TokenLookup.Unavailable -> return failure("token refresh unavailable")
            }
        book.asin?.let { asin ->
            rateLimiter.await()
            when (val result = graphQl.editionRatingByAsin(token, asin)) {
                is HardcoverRatingResult.Found -> return found(result)
                HardcoverRatingResult.NotFound -> Unit
                HardcoverRatingResult.Unauthorized -> return brokenConnection("token rejected")
                is HardcoverRatingResult.Unavailable -> return failure(result.detail)
            }
        }
        book.isbn?.let { isbn ->
            rateLimiter.await()
            when (val result = graphQl.editionRatingByIsbn(token, isbn)) {
                is HardcoverRatingResult.Found -> return found(result)
                HardcoverRatingResult.NotFound -> Unit
                HardcoverRatingResult.Unauthorized -> return brokenConnection("token rejected")
                is HardcoverRatingResult.Unavailable -> return failure(result.detail)
            }
        }
        return ratingByTitle(token, book)
    }

    private suspend fun ratingByTitle(
        token: String,
        book: BookIdentity,
    ): AppResult<ExternalRatingMeta?> {
        rateLimiter.await()
        return when (val result = graphQl.booksByTitle(token, book.title)) {
            HardcoverCandidatesResult.Unauthorized -> {
                brokenConnection("token rejected")
            }

            is HardcoverCandidatesResult.Unavailable -> {
                failure(result.detail)
            }

            is HardcoverCandidatesResult.Found -> {
                AppResult.Success(
                    result.candidates
                        .filter { it.count > 0 && it.average != null }
                        .filter {
                            MatchScorer.isConfidentRatingMatch(
                                book,
                                BookMatch(title = it.title, author = it.author, score = 0.0),
                            )
                        }.maxByOrNull { it.count }
                        ?.let { ExternalRatingMeta(average = requireNotNull(it.average), count = it.count) },
                )
            }
        }
    }

    private fun found(result: HardcoverRatingResult.Found): AppResult<ExternalRatingMeta?> =
        AppResult.Success(ExternalRatingMeta(average = result.average, count = result.count))

    private fun brokenConnection(detail: String): AppResult.Failure =
        AppResult.Failure(HardcoverError.ConnectionBroken(debugInfo = "hardcover rating: $detail"))

    private fun failure(detail: String): AppResult.Failure =
        AppResult.Failure(HardcoverError.Unavailable(debugInfo = "hardcover rating: $detail"))
}
