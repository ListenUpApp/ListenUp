package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.MetadataRateBucket
import com.calypsan.listenup.server.auth.MetadataRateLimiter
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RateDecision
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.matching.BookFinder
import com.calypsan.listenup.server.matching.resolveFindRegion
import com.calypsan.listenup.server.matching.toFindSubject

private const val MAX_FIND_QUERY_LENGTH = 200

/**
 * Server side of [MatchingService]. Find is gated like an edit — the caller needs `canEdit` and must be able
 * to see the book; a denial reads exactly like a missing book, so it is no existence oracle — and shares the
 * per-user metadata search throttle. [loadBook] and [libraryRegion] read the book and its library's store.
 * Route handlers call [copyWith] to bind the caller per request.
 */
internal class MatchingServiceImpl(
    private val finder: BookFinder,
    private val loadBook: suspend (BookId) -> BookSyncPayload?,
    private val libraryRegion: suspend (LibraryId) -> String?,
    private val permissionPolicy: UserPermissionPolicy,
    private val bookAccessPolicy: BookAccessPolicy,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    private val rateLimiter: MetadataRateLimiter? = null,
) : MatchingService {
    /** A copy scoped to [principal]; route handlers call this per request. */
    fun copyWith(principal: PrincipalProvider): MatchingServiceImpl =
        MatchingServiceImpl(
            finder = finder,
            loadBook = loadBook,
            libraryRegion = libraryRegion,
            permissionPolicy = permissionPolicy,
            bookAccessPolicy = bookAccessPolicy,
            principal = principal,
            rateLimiter = rateLimiter,
        )

    override suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest,
    ): AppResult<BookFindResult> {
        enforceRate()?.let { return AppResult.Failure(it) }
        requireEditableBook(bookId)?.let { return AppResult.Failure(it) }
        rejectUnusable(request)?.let { return AppResult.Failure(it) }
        val book = loadBook(bookId) ?: return AppResult.Failure(notFound(bookId))
        val region = resolveFindRegion(request.regionOverride, libraryRegion(book.libraryId))
        return AppResult.Success(finder.find(book.toFindSubject(), request, region))
    }

    private suspend fun requireEditableBook(bookId: BookId): AppError? {
        val caller = principal.current() ?: return AuthError.PermissionDenied()
        permissionPolicy.requireCanEdit(caller.userId, caller.role)?.let { return it }
        val canSee = bookAccessPolicy.canAccess(caller.userId.value, caller.role, bookId.value)
        return if (canSee) null else notFound(bookId)
    }

    /** Per-user throttle; a no-op when no limiter or no principal is bound (direct-construction tests). */
    private suspend fun enforceRate(): AppError? {
        val limiter = rateLimiter ?: return null
        val userId = principal.current()?.userId?.value ?: return null
        return when (val decision = limiter.check(MetadataRateBucket.SEARCH, userId)) {
            RateDecision.Allowed -> null
            is RateDecision.Throttled -> AuthError.RateLimited(retryAfterSeconds = decision.retryAfterSeconds)
        }
    }

    private fun rejectUnusable(request: BookFindRequest): AppError? {
        val override = request.regionOverride
        return when {
            (request.query?.length ?: 0) > MAX_FIND_QUERY_LENGTH -> {
                MetadataError.Malformed(debugInfo = "find query longer than $MAX_FIND_QUERY_LENGTH")
            }

            override != null && MetadataLocale.SUPPORTED.none { it.region == override.region } -> {
                MetadataError.Malformed(debugInfo = "unsupported store ${override.region}")
            }

            else -> {
                null
            }
        }
    }

    private fun notFound(bookId: BookId) = MetadataError.NotFound(debugInfo = "no book for id ${bookId.value}")
}
