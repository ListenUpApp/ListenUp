package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.UndoResult
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.matching.person.PersonMatchApplier
import com.calypsan.listenup.server.matching.person.PersonMatchUndoer
import com.calypsan.listenup.server.matching.person.PersonReviewer
import com.calypsan.listenup.server.matching.undo.ReceiptEntity
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.server.matching.apply.BookMatchApplier
import com.calypsan.listenup.server.matching.review.BookReviewer
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.matching.undo.MatchUndoer
import com.calypsan.listenup.server.sync.withCapturedFrames
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
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.matching.BookFinder
import com.calypsan.listenup.server.matching.PeopleFinder
import com.calypsan.listenup.server.matching.PeopleSubject
import com.calypsan.listenup.server.matching.resolveFindRegion
import com.calypsan.listenup.server.matching.toFindSubject

private const val MAX_FIND_QUERY_LENGTH = 200

/** Match details' Review, Apply and Undo, bundled for [MatchingServiceImpl]. */
internal class MatchDetails(
    val reviewer: BookReviewer,
    val applier: BookMatchApplier,
    val undoer: MatchUndoer,
    val receipts: MatchReceiptStore,
    val people: PersonMatchDetails,
)

/** Match details' person Review, Apply and Undo. [loadPerson] reads a live contributor, or null. */
internal class PersonMatchDetails(
    val reviewer: PersonReviewer,
    val applier: PersonMatchApplier,
    val undoer: PersonMatchUndoer,
    val loadPerson: suspend (ContributorId) -> ContributorSyncPayload?,
)

/**
 * Server side of [MatchingService]. Find, Review, Apply and Undo are all gated like an edit — the caller needs
 * `canEdit` and must be able to see the book; a denial reads exactly like a missing book, so it is no existence oracle — and shares the
 * per-user metadata search throttle. [loadBook] and [libraryRegion] read the book and its library's store;
 * [loadPeople] and [peopleRegion] a people Find's subject (only books the caller can see) and the library's store.
 * Route handlers call [copyWith] to bind the caller per request.
 */
internal class MatchingServiceImpl(
    private val finder: BookFinder,
    private val loadBook: suspend (BookId) -> BookSyncPayload?,
    private val libraryRegion: suspend (LibraryId) -> String?,
    private val permissionPolicy: PermissionPolicy,
    private val bookAccessPolicy: BookAccessPolicy,
    private val peopleFinder: PeopleFinder,
    private val loadPeople: suspend (ContributorId, UserPrincipal) -> PeopleSubject?,
    private val peopleRegion: suspend () -> MetadataLocale,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    private val details: MatchDetails,
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
            details = details,
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
        permissionPolicy.require(caller, Permission.EDIT_METADATA)?.let { return AppResult.Failure(it) }
        rejectUnusable(request)?.let { return AppResult.Failure(it) }
        val subject =
            loadPeople(contributorId, caller)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "no contributor for id ${contributorId.value}"),
                )
        return AppResult.Success(peopleFinder.find(subject, request, peopleRegion()))
    }

    private fun rejectUnusable(request: PersonFindRequest): AppError? =
        if ((request.query?.length ?: 0) > MAX_FIND_QUERY_LENGTH) {
            MetadataError.Malformed(debugInfo = "find query longer than $MAX_FIND_QUERY_LENGTH")
        } else {
            null
        }

    override suspend fun reviewBookMatch(
        bookId: BookId,
        candidate: BookCandidateKey,
        region: MetadataLocale?,
    ): AppResult<BookMatchReview> {
        enforceRate(MetadataRateBucket.FETCH)?.let { return AppResult.Failure(it) }
        requireEditableBook(bookId)?.let { return AppResult.Failure(it) }
        unsupportedStore(region)?.let { return AppResult.Failure(it) }
        val book = loadBook(bookId) ?: return AppResult.Failure(notFound(bookId))
        return details.reviewer.review(book, candidate, matchLocale(candidate, region, book)).map { it.review }
    }

    override suspend fun applyBookMatch(
        bookId: BookId,
        request: BookMatchApply,
    ): AppResult<Mutated<MatchReceipt>> {
        requireEditableBook(bookId)?.let { return AppResult.Failure(it) }
        unsupportedStore(request.region)?.let { return AppResult.Failure(it) }
        val caller = principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        val book = loadBook(bookId) ?: return AppResult.Failure(notFound(bookId))
        val locale = matchLocale(request.candidate, request.region, book)
        return withCapturedFrames { details.applier.apply(book, request, locale, caller.userId.value) }
    }

    override suspend fun reviewPersonMatch(
        contributorId: ContributorId,
        candidate: PersonCandidateKey,
        role: ContributorRole?,
    ): AppResult<PersonMatchReview> {
        enforceRate(MetadataRateBucket.FETCH)?.let { return AppResult.Failure(it) }
        requireEditor()?.let { return AppResult.Failure(it) }
        val person = details.people.loadPerson(contributorId) ?: return AppResult.Failure(notFound(contributorId))
        return details.people.reviewer
            .review(person, candidate, personLocale(candidate))
            .map { it.review.copy(role = role) }
    }

    override suspend fun applyPersonMatch(
        contributorId: ContributorId,
        request: PersonMatchApply,
    ): AppResult<Mutated<MatchReceipt>> {
        requireEditor()?.let { return AppResult.Failure(it) }
        val caller = principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        val person = details.people.loadPerson(contributorId) ?: return AppResult.Failure(notFound(contributorId))
        val locale = personLocale(request.candidate)
        return withCapturedFrames { details.people.applier.apply(person, request, locale, caller.userId.value) }
    }

    override suspend fun undoMatch(receiptId: String): AppResult<Mutated<UndoResult>> {
        val receipt =
            details.receipts.find(receiptId)
                ?: return AppResult.Failure(MetadataError.UndoExpired(debugInfo = "no receipt $receiptId"))
        return when (receipt.entity) {
            ReceiptEntity.BOOK.value -> {
                requireEditableBook(BookId(receipt.entityId))?.let { return AppResult.Failure(it) }
                withCapturedFrames { details.undoer.undo(receiptId) }
            }

            ReceiptEntity.CONTRIBUTOR.value -> {
                requireEditor()?.let { return AppResult.Failure(it) }
                withCapturedFrames { details.people.undoer.undo(receiptId) }
            }

            else -> {
                AppResult.Failure(MetadataError.UndoExpired(debugInfo = "receipt $receiptId is for ${receipt.entity}"))
            }
        }
    }

    /** People have no per-book visibility: editing them needs only `canEdit`. */
    private suspend fun requireEditor(): AppError? {
        val caller = principal.current() ?: return AuthError.PermissionDenied()
        return permissionPolicy.require(caller, Permission.EDIT_METADATA)
    }

    /** The store a person Review reads: the Audible ref's own, else the library's. */
    private suspend fun personLocale(candidate: PersonCandidateKey): MetadataLocale =
        candidate.refs
            .firstOrNull { it.provider == ExternalRef.AUDIBLE }
            ?.region
            ?.takeIf { ref -> MetadataLocale.SUPPORTED.any { it.region == ref } }
            ?.let(::MetadataLocale)
            ?: peopleRegion()

    private fun notFound(contributorId: ContributorId) =
        MetadataError.NotFound(debugInfo = "no contributor for id ${contributorId.value}")

    /** The store a Review reads: the Audible ref's own, else this request's, else the library's, else the default. */
    private suspend fun matchLocale(
        candidate: BookCandidateKey,
        region: MetadataLocale?,
        book: BookSyncPayload,
    ): MetadataLocale =
        candidate.refs
            .firstOrNull { it.provider == ExternalRef.AUDIBLE }
            ?.region
            ?.takeIf { ref -> MetadataLocale.SUPPORTED.any { it.region == ref } }
            ?.let(::MetadataLocale)
            ?: resolveFindRegion(region, libraryRegion(book.libraryId)).locale

    private fun unsupportedStore(region: MetadataLocale?): AppError? =
        region?.takeIf { r -> MetadataLocale.SUPPORTED.none { it.region == r.region } }?.let {
            MetadataError.Malformed(debugInfo = "unsupported store ${it.region}")
        }

    private suspend fun requireEditableBook(bookId: BookId): AppError? {
        val caller = principal.current() ?: return AuthError.PermissionDenied()
        permissionPolicy.require(caller, Permission.EDIT_METADATA)?.let { return it }
        val canSee = bookAccessPolicy.canAccess(caller.userId.value, caller.role, bookId.value)
        return if (canSee) null else notFound(bookId)
    }

    /** Per-user throttle; a no-op when no limiter or no principal is bound (direct-construction tests). */
    private suspend fun enforceRate(bucket: MetadataRateBucket = MetadataRateBucket.SEARCH): AppError? {
        val limiter = rateLimiter ?: return null
        val userId = principal.current()?.userId?.value ?: return null
        return when (val decision = limiter.check(bucket, userId)) {
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
