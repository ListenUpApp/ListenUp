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
 * How Hardcover's readers rate a book, read through [catalogToken]: the admin's API token when one is
 * stored and accepted (#1542), else a borrowed connected account. Lookup order: the Audible ASIN, then the
 * ISBN, then the title, where a title candidate counts only when [MatchScorer.isConfidentRatingMatch]
 * accepts its author: a wrong score is worse than none. Of several confident title matches, the
 * most-rated one wins, since Hardcover often holds duplicate records of one book and the busiest carries
 * the readers.
 *
 * Available with a usable API token, whatever else is configured. Without one, unavailable without a
 * client id ([clientConfigured]) or a healthy connection; both are standing conditions the fetcher skips
 * without penalty. A 401 on the API token marks it rejected and the lookup runs again on a connected
 * account. A broken connection or a rejected connection token is a [HardcoverError.ConnectionBroken]
 * failure; an unreachable Hardcover is [HardcoverError.Unavailable]. Both count toward the automatic pause.
 */
class HardcoverRatingSource(
    private val graphQl: HardcoverGraphQlClient,
    private val catalogToken: HardcoverCatalogToken,
    private val rateLimiter: HardcoverRateLimiter,
    private val clientConfigured: Boolean,
) : RatingSource {
    override val id: MetadataProviderId = MetadataProviderId.HARDCOVER
    override val ratingSource: ExternalRatingSource = ExternalRatingSource.HARDCOVER

    override suspend fun availability(): RatingSourceAvailability =
        when {
            catalogToken.apiToken() != null -> RatingSourceAvailability.Available
            !clientConfigured -> RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NOT_CONFIGURED)
            !catalogToken.isAvailable() -> RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NO_CONNECTION)
            else -> RatingSourceAvailability.Available
        }

    override suspend fun getRating(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ExternalRatingMeta?> {
        catalogToken.apiToken()?.let { admin ->
            when (val attempt = ratingWith(admin, book)) {
                is RatingAttempt.Done -> return attempt.result
                RatingAttempt.Rejected -> catalogToken.markApiTokenRejected(admin)
            }
        }
        val token =
            when (val lookup = catalogToken.connectionToken()) {
                is TokenLookup.Valid -> lookup.accessToken
                TokenLookup.NotConnected -> return AppResult.Success(null)
                is TokenLookup.Broken -> return brokenConnection("connection broken: ${lookup.reason}")
                TokenLookup.Unavailable -> return failure("token refresh unavailable")
            }
        return when (val attempt = ratingWith(token, book)) {
            is RatingAttempt.Done -> attempt.result
            RatingAttempt.Rejected -> brokenConnection("token rejected")
        }
    }

    /** One full lookup (ASIN, ISBN, title) with [token]; [RatingAttempt.Rejected] when Hardcover answers 401. */
    private suspend fun ratingWith(
        token: String,
        book: BookIdentity,
    ): RatingAttempt {
        book.asin?.let { asin ->
            rateLimiter.await()
            when (val result = graphQl.editionRatingByAsin(token, asin)) {
                is HardcoverRatingResult.Found -> return RatingAttempt.Done(found(result))
                HardcoverRatingResult.NotFound -> Unit
                HardcoverRatingResult.Unauthorized -> return RatingAttempt.Rejected
                is HardcoverRatingResult.Unavailable -> return RatingAttempt.Done(failure(result.detail))
            }
        }
        book.isbn?.let { isbn ->
            rateLimiter.await()
            when (val result = graphQl.editionRatingByIsbn(token, isbn)) {
                is HardcoverRatingResult.Found -> return RatingAttempt.Done(found(result))
                HardcoverRatingResult.NotFound -> Unit
                HardcoverRatingResult.Unauthorized -> return RatingAttempt.Rejected
                is HardcoverRatingResult.Unavailable -> return RatingAttempt.Done(failure(result.detail))
            }
        }
        return ratingByTitle(token, book)
    }

    private suspend fun ratingByTitle(
        token: String,
        book: BookIdentity,
    ): RatingAttempt {
        rateLimiter.await()
        return when (val result = graphQl.booksByTitle(token, book.title)) {
            HardcoverCandidatesResult.Unauthorized -> {
                RatingAttempt.Rejected
            }

            is HardcoverCandidatesResult.Unavailable -> {
                RatingAttempt.Done(failure(result.detail))
            }

            is HardcoverCandidatesResult.Found -> {
                RatingAttempt.Done(
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
                    ),
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

    /** One lookup's outcome with one token: an answer, or Hardcover refusing the token. */
    private sealed interface RatingAttempt {
        /** The lookup finished with [result], found or not. */
        data class Done(
            val result: AppResult<ExternalRatingMeta?>,
        ) : RatingAttempt

        data object Rejected : RatingAttempt
    }
}
