package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ExternalRatingsCheck
import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * A listener's rating of a book. The caller is always the principal — nobody can write another
 * listener's row. Both calls are idempotent (safe for the outbox to re-fire): re-rating overwrites,
 * and clearing a missing rating succeeds. A book the caller cannot open answers `NotFound`.
 */
@Rpc
interface BookRatingService {
    /** Set the caller's rating of [bookId]. */
    suspend fun rate(
        bookId: BookId,
        request: RateBookRequest,
    ): AppResult<Unit>

    /** Remove the caller's rating of [bookId]. */
    suspend fun clearRating(bookId: BookId): AppResult<Unit>

    /**
     * Re-fetch every enabled outside source for [bookId] now — admin only. Answers
     * `RatingError.SourceUnavailable` when every enabled source failed.
     */
    suspend fun refreshExternalRatings(bookId: BookId): AppResult<Unit>

    /**
     * Book Detail opened [bookId]: if its Hardcover rating is missing, or older than the server's
     * freshness window, the server fetches it in the background (#1542). Answers at once, before any
     * fetch, and never fails because a fetch did; the rating, when it comes, arrives through the ratings
     * sync. Any listener who can open the book may ask; one who can't gets `NotFound`. Idempotent.
     *
     * Kept for clients that predate [checkExternalRatings]; current clients call that instead.
     */
    suspend fun ensureExternalRatings(bookId: BookId): AppResult<Unit>

    /**
     * Book Detail opened [bookId]: start its Hardcover fetch when the rating is missing or stale, exactly
     * as [ensureExternalRatings] does, and say how it goes. Emits [ExternalRatingsCheck.CHECKING] when a
     * fetch is running — this open's, or one already in flight for the book — then
     * [ExternalRatingsCheck.DONE] when it ends, fails or outlasts the server's bound, and completes.
     * Completes without emitting when nothing needs fetching, when Hardcover can't be asked, or when the
     * caller can't open the book. The rating itself arrives through the ratings sync.
     */
    fun checkExternalRatings(bookId: BookId): Flow<RpcEvent<ExternalRatingsCheck>>
}
