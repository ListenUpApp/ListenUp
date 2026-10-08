package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.BookRatingService
import com.calypsan.listenup.api.dto.ExternalRatingsCheck
import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.RatingError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.server.auth.OpenToAllMembers
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.ratings.ExternalRatingsFetcher
import com.calypsan.listenup.server.ratings.HardcoverRatingOnOpen
import com.calypsan.listenup.server.sync.BookRatingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * [BookRatingService] on the authed mount. The listener is always the principal, and a book the
 * caller cannot open answers `NotFound`, never revealing that it exists. Input arrives already
 * validated by [RateBookRequest]'s `init`.
 *
 * [refreshExternalRatings] is the one admin-only method here: gated by [requireAdmin], same as its
 * neighbours in [AdminSettingsServiceImpl]. [fetcher] is nullable — non-null in production, absent
 * in the direct-construction unit tests that never call this method, where it is a no-op.
 *
 * [ensureExternalRatings] and [checkExternalRatings] are open to any listener who can open the book;
 * they only ever start (or follow) a background fetch.
 */
class BookRatingServiceImpl(
    private val ratings: BookRatingRepository,
    private val accessPolicy: BookAccessPolicy,
    private val principal: PrincipalProvider,
    private val fetcher: ExternalRatingsFetcher? = null,
    /** Ratings on open (#1542). Nullable on the same terms as [fetcher]: absent in direct-construction tests. */
    private val onOpen: HardcoverRatingOnOpen? = null,
) : BookRatingService {
    @OpenToAllMembers(reason = "the caller's own rating, on a book they can see")
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

    @OpenToAllMembers(reason = "the caller's own rating, on a book they can see")
    override suspend fun clearRating(bookId: BookId): AppResult<Unit> {
        val caller = callerWithAccessTo(bookId) ?: return notFound(bookId)
        return ratings.clear(bookId = bookId.value, userId = caller)
    }

    override suspend fun refreshExternalRatings(bookId: BookId): AppResult<Unit> {
        requireAdmin()?.let { return it }
        callerWithAccessTo(bookId) ?: return notFound(bookId)
        val fetch = fetcher ?: return AppResult.Failure(RatingError.SourceUnavailable(debugInfo = "not wired"))

        val outcome = fetch.refresh(bookId)
        return if (outcome.tried > 0 && outcome.answered == 0) {
            AppResult.Failure(RatingError.SourceUnavailable())
        } else {
            AppResult.Success(Unit)
        }
    }

    /** Returns a copy scoped to [principal]; the route handler calls this per request. */
    @OpenToAllMembers(reason = "starts a fetch of public scores for a book the caller can see")
    override suspend fun ensureExternalRatings(bookId: BookId): AppResult<Unit> {
        callerWithAccessTo(bookId) ?: return notFound(bookId)
        onOpen?.ensure(bookId)
        return AppResult.Success(Unit)
    }

    @OpenToAllMembers(reason = "starts a fetch of public scores for a book the caller can see")
    override fun checkExternalRatings(bookId: BookId): Flow<RpcEvent<ExternalRatingsCheck>> =
        flow {
            if (callerWithAccessTo(bookId) == null) return@flow
            val fetch = onOpen?.ensure(bookId) ?: return@flow
            emit(RpcEvent.Data(ExternalRatingsCheck.CHECKING))
            withTimeoutOrNull(EXTERNAL_CHECK_BOUND) { fetch.join() }
            emit(RpcEvent.Data(ExternalRatingsCheck.DONE))
        }

    fun copyWith(principal: PrincipalProvider): BookRatingServiceImpl =
        BookRatingServiceImpl(ratings, accessPolicy, principal, fetcher, onOpen)

    private suspend fun callerWithAccessTo(bookId: BookId): String? {
        val p = principal.current() ?: return null
        return p.userId.value.takeIf { accessPolicy.canAccess(it, p.role, bookId.value) }
    }

    private fun notFound(bookId: BookId): AppResult.Failure =
        AppResult.Failure(SyncError.NotFound(domain = "book", entityId = bookId.value))

    /** null = allowed; a Failure (PermissionDenied / SessionExpired) otherwise. */
    private fun requireAdmin(): AppResult.Failure? {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        return if (caller.role == UserRole.ROOT || caller.role == UserRole.ADMIN) {
            null
        } else {
            AppResult.Failure(AuthError.PermissionDenied())
        }
    }
}

/**
 * How long [BookRatingServiceImpl.checkExternalRatings] follows a fetch before saying DONE anyway:
 * Hardcover's 15 s HTTP timeout, plus the shared rate limiter's queue.
 */
private val EXTERNAL_CHECK_BOUND = 20.seconds
