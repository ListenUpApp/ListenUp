package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.BookRatingService
import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.error.RatingError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.sync.BookRatingRepository

/**
 * [BookRatingService] on the authed mount. The listener is always the principal, and a book the
 * caller cannot open answers `NotFound`, never revealing that it exists. Input arrives already
 * validated by [RateBookRequest]'s `init`.
 */
class BookRatingServiceImpl(
    private val ratings: BookRatingRepository,
    private val accessPolicy: BookAccessPolicy,
    private val principal: PrincipalProvider,
) : BookRatingService {
    override suspend fun rate(
        bookId: BookId,
        request: RateBookRequest,
    ): AppResult<Unit> {
        val caller = callerWithAccessTo(bookId) ?: return notFound(bookId)
        return ratings
            .upsert(
                BookRatingSyncPayload(
                    id = request.candidateId,
                    bookId = bookId.value,
                    userId = caller,
                    halfStars = request.halfStars,
                    note = ListenerRatingLimits.normalizeNote(request.note),
                    // Stamped by BookRatingRepository.writePayload for a fresh row; never stored as 0.
                    ratedAt = 0L,
                    updatedAt = 0L,
                    revision = 0L,
                ),
            ).map { }
    }

    override suspend fun clearRating(bookId: BookId): AppResult<Unit> {
        val caller = callerWithAccessTo(bookId) ?: return notFound(bookId)
        return ratings.clear(bookId = bookId.value, userId = caller)
    }

    // wired in Task 5
    override suspend fun refreshExternalRatings(bookId: BookId): AppResult<Unit> =
        AppResult.Failure(RatingError.SourceUnavailable(debugInfo = "not wired yet"))

    /** Returns a copy scoped to [principal]; the route handler calls this per request. */
    fun copyWith(principal: PrincipalProvider): BookRatingServiceImpl =
        BookRatingServiceImpl(ratings, accessPolicy, principal)

    private suspend fun callerWithAccessTo(bookId: BookId): String? {
        val p = principal.current() ?: return null
        return p.userId.value.takeIf { accessPolicy.canAccess(it, p.role, bookId.value) }
    }

    private fun notFound(bookId: BookId): AppResult.Failure =
        AppResult.Failure(SyncError.NotFound(domain = "book", entityId = bookId.value))
}
