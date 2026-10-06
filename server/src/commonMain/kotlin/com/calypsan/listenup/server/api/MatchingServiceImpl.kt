package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.MetadataRateBucket
import com.calypsan.listenup.server.auth.MetadataRateLimiter
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RateDecision
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.matching.BookFinder
import com.calypsan.listenup.server.matching.PeopleFinder
import com.calypsan.listenup.server.matching.PeopleSubject
import com.calypsan.listenup.server.matching.resolveFindRegion
import com.calypsan.listenup.server.matching.toFindSubject

private const val MAX_FIND_QUERY_LENGTH = 200

/** The roles a people Find offers (spec: only authors and narrators). */
private val SEARCHABLE_ROLES = setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)

/**
 * Server side of [MatchingService]. Find is gated like an edit — the caller needs `canEdit` and must be able
 * to see the book; a denial reads exactly like a missing book, so it is no existence oracle — and shares the
 * per-user metadata search throttle. [loadBook] and [libraryRegion] read the book and its library's store;
 * [loadPeople] and [peopleRegion] a people Find's subject (only books the caller can see) and the library's store.
 * Route handlers call [copyWith] to bind the caller per request.
 */
internal class MatchingServiceImpl(
    private val finder: BookFinder,
    private val loadBook: suspend (BookId) -> BookSyncPayload?,
    private val libraryRegion: suspend (LibraryId) -> String?,
    private val permissionPolicy: UserPermissionPolicy,
    private val bookAccessPolicy: BookAccessPolicy,
    private val peopleFinder: PeopleFinder,
    private val loadPeople: suspend (ContributorId, ContributorRole, UserPrincipal) -> PeopleSubject?,
    private val peopleRegion: suspend () -> MetadataLocale,
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
            peopleFinder = peopleFinder,
            loadPeople = loadPeople,
            peopleRegion = peopleRegion,
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

    override suspend fun findPeople(
        contributorId: ContributorId,
        request: PersonFindRequest,
    ): AppResult<PersonFindResult> {
        enforceRate()?.let { return AppResult.Failure(it) }
        val caller = principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        permissionPolicy.requireCanEdit(caller.userId, caller.role)?.let { return AppResult.Failure(it) }
        rejectUnusable(request)?.let { return AppResult.Failure(it) }
        val subject =
            loadPeople(contributorId, request.role, caller)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "no contributor for id ${contributorId.value}"),
                )
        return AppResult.Success(peopleFinder.find(subject, request, peopleRegion()))
    }

    private fun rejectUnusable(request: PersonFindRequest): AppError? =
        when {
            (request.query?.length ?: 0) > MAX_FIND_QUERY_LENGTH -> {
                MetadataError.Malformed(debugInfo = "find query longer than $MAX_FIND_QUERY_LENGTH")
            }

            request.role !in SEARCHABLE_ROLES -> {
                MetadataError.Malformed(debugInfo = "people are found as authors or narrators, not ${request.role}")
            }

            else -> {
                null
            }
        }

    override suspend fun reviewBookMatch(
        bookId: BookId,
        candidate: com.calypsan.listenup.api.dto.match.BookCandidateKey,
        region: MetadataLocale?,
    ): AppResult<com.calypsan.listenup.api.dto.match.BookMatchReview> = AppResult.Failure(notFound(bookId))

    override suspend fun applyBookMatch(
        bookId: BookId,
        request: com.calypsan.listenup.api.dto.match.BookMatchApply,
    ): AppResult<com.calypsan.listenup.api.sync.Mutated<com.calypsan.listenup.api.dto.match.MatchReceipt>> =
        AppResult.Failure(notFound(bookId))

    override suspend fun undoMatch(
        receiptId: String,
    ): AppResult<com.calypsan.listenup.api.sync.Mutated<com.calypsan.listenup.api.dto.match.UndoResult>> =
        AppResult.Failure(MetadataError.UndoExpired())

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
