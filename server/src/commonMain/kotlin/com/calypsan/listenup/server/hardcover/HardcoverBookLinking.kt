package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.BookAccessPolicy

/**
 * The manual side of matching (spec B4), behind [com.calypsan.listenup.api.HardcoverService]:
 * search Hardcover's catalog, link a book to the user's pick (which unparks its waiting pushes),
 * unlink it for "Change match", list the books that need a match, and describe one book's match. Search costs one Hardcover `search` plus one lookup for the editions,
 * both paced by the shared [HardcoverRateLimiter]. A link or unlink tells the pull
 * ([HardcoverPullRequests.onMatchChanged]): reads pulled through the old match no longer belong to the
 * book, and the whole shelf is re-read.
 */
class HardcoverBookLinking(
    private val graphQl: HardcoverGraphQlClient,
    private val tokens: HardcoverTokenProvider,
    private val connections: HardcoverConnectionStore,
    private val links: HardcoverBookLinkStore,
    private val outbox: HardcoverOutbox,
    private val nudge: HardcoverPushNudge,
    private val access: BookAccessPolicy,
    private val rateLimiter: HardcoverRateLimiter,
    private val pulls: HardcoverPullRequests,
    private val catalog: HardcoverCatalogCache,
) {
    /** Catalog candidates for [query], best first, through [userId]'s connection. */
    suspend fun searchCatalog(
        userId: String,
        query: String,
    ): AppResult<List<HardcoverBookCandidate>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return AppResult.Failure(
                ValidationError(message = "Type a title or an author to search for.", field = "query"),
            )
        }
        val token =
            when (val lookup = tokens.accessToken(userId)) {
                is TokenLookup.Valid -> lookup.accessToken

                TokenLookup.NotConnected -> return AppResult.Failure(HardcoverError.NotConnected())

                is TokenLookup.Broken -> return AppResult.Failure(
                    HardcoverError.ConnectionBroken(debugInfo = "search: ${lookup.reason}"),
                )

                TokenLookup.Unavailable -> return AppResult.Failure(
                    HardcoverError.Unavailable(debugInfo = "search: token refresh unavailable"),
                )
            }
        val session = SearchSession(userId, token)
        val hits = session.call { graphQl.searchBooks(it, trimmed) }.valueOr { return it.toFailure("searchBooks") }
        if (hits.isEmpty()) return AppResult.Success(emptyList())
        val books =
            session
                .call { graphQl.booksByIds(it, hits.map { hit -> hit.bookId }) }
                .valueOr { return it.toFailure("booksByIds") }
                .associateBy { it.id }
        catalog.remember(books.values)
        return AppResult.Success(
            hits.map { hit ->
                val book = books[hit.bookId]
                HardcoverBookCandidate(
                    hcBookId = hit.bookId,
                    hcEditionId = book?.defaultAudioEditionId,
                    title = book?.title ?: hit.title,
                    authors = book?.authors?.takeIf { it.isNotEmpty() } ?: hit.authors,
                    releaseYear = book?.releaseYear ?: hit.releaseYear,
                    ratingsCount = book?.count,
                )
            },
        )
    }

    /**
     * Links [bookId] to [hcBookId] (as [hcEditionId]) for [userId], recorded as made by [method] — the
     * user's pick, or the original method of a match an Undo restores — and sends the pushes that were waiting.
     */
    suspend fun link(
        userId: String,
        role: UserRole,
        bookId: String,
        hcBookId: Long,
        hcEditionId: Long?,
        method: HardcoverMatchMethod = HardcoverMatchMethod.MANUAL,
    ): AppResult<Unit> {
        if (hcBookId <= 0 || (hcEditionId != null && hcEditionId <= 0)) {
            return AppResult.Failure(ValidationError(message = "That isn't a Hardcover book.", field = "hcBookId"))
        }
        if (!access.canAccess(
                userId,
                role,
                bookId,
            )
        ) {
            return AppResult.Failure(BookError.NotFound(debugInfo = "bookId=$bookId"))
        }
        if (!connections.hasConnection(userId)) return AppResult.Failure(HardcoverError.NotConnected())
        links.linkManually(userId, bookId, hcBookId, hcEditionId, method)
        outbox.unpark(userId, bookId)
        nudge.nudge(userId)
        pulls.onMatchChanged(userId, bookId)
        return AppResult.Success(Unit)
    }

    /** Forgets [bookId]'s match for [userId]: it needs a match again, and its pushes wait. */
    suspend fun unlink(
        userId: String,
        role: UserRole,
        bookId: String,
    ): AppResult<Unit> {
        if (!access.canAccess(
                userId,
                role,
                bookId,
            )
        ) {
            return AppResult.Failure(BookError.NotFound(debugInfo = "bookId=$bookId"))
        }
        links.unlink(userId, bookId)
        pulls.onMatchChanged(userId, bookId)
        return AppResult.Success(Unit)
    }

    /** [userId]'s books that need a match and that they can still see, newest first. */
    suspend fun booksNeedingMatch(
        userId: String,
        role: UserRole,
    ): AppResult<List<String>> {
        if (!connections.hasConnection(userId)) return AppResult.Failure(HardcoverError.NotConnected())
        return AppResult.Success(links.booksNeedingMatch(userId).filter { access.canAccess(userId, role, it) })
    }

    /** How [bookId] is matched for [userId]; a linked book is named from [catalog] when Hardcover can be asked. */
    suspend fun bookMatch(
        userId: String,
        role: UserRole,
        bookId: String,
    ): AppResult<HardcoverBookMatch> {
        if (!access.canAccess(userId, role, bookId)) {
            return AppResult.Failure(BookError.NotFound(debugInfo = "bookId=$bookId"))
        }
        if (!connections.hasConnection(userId)) return AppResult.Failure(HardcoverError.NotConnected())
        val link = links.linkFor(userId, bookId) ?: return AppResult.Success(HardcoverBookMatch.Unmatched)
        val hcBookId = link.hcBookId
        if (!link.isLinked || hcBookId == null) return AppResult.Success(HardcoverBookMatch.NeedsMatch)
        val book =
            catalog.cached(hcBookId)
                ?: (
                    tokens.accessToken(
                        userId,
                    ) as? TokenLookup.Valid
                )?.let { catalog.describe(it.accessToken, hcBookId) }
        return AppResult.Success(
            HardcoverBookMatch.Linked(
                hcBookId = hcBookId,
                hcEditionId = link.hcEditionId,
                title = book?.title,
                authors = book?.authors.orEmpty(),
                releaseYear = book?.releaseYear,
                chosenByYou = link.method == HardcoverMatchMethod.MANUAL,
                sync = bookSyncOf(link, outbox.pendingCountFor(userId, bookId)),
                method = link.method,
            ),
        )
    }

    /**
     * One search's access token. Hardcover may reject a token before it expires (reset or revoked on
     * its side), so a 401 refreshes it once and asks again — the path push and pull take
     * ([HardcoverTokenProvider.refreshAfterRejection]). Only a rejection after that refresh reads as a
     * broken connection; a refresh that can't reach Hardcover reads as Hardcover being unavailable.
     */
    private inner class SearchSession(
        private val userId: String,
        private var token: String,
    ) {
        private var refreshed = false

        suspend fun <T> call(request: suspend (accessToken: String) -> HardcoverCall<T>): HardcoverCall<T> {
            rateLimiter.await()
            val answer = request(token)
            if (answer != HardcoverCall.Unauthorized || refreshed) return answer
            refreshed = true
            token =
                when (val lookup = tokens.refreshAfterRejection(userId, token)) {
                    is TokenLookup.Valid -> lookup.accessToken
                    TokenLookup.Unavailable -> return HardcoverCall.Failed("token refresh unavailable")
                    TokenLookup.NotConnected, is TokenLookup.Broken -> return answer
                }
            rateLimiter.await()
            return request(token)
        }
    }

    private fun HardcoverCall<Nothing>.toFailure(what: String): AppResult.Failure =
        AppResult.Failure(
            when (this) {
                HardcoverCall.Unauthorized, is HardcoverCall.MissingScope -> {
                    HardcoverError.ConnectionBroken(
                        debugInfo = "$what: $this",
                    )
                }

                else -> {
                    HardcoverError.Unavailable(debugInfo = "$what: $this")
                }
            },
        )
}

/**
 * Where a linked book stands, from what the server already stores. The deletion rule outranks
 * everything: a suppressed listen-through sends nothing, whatever is queued. Then anything queued.
 * Then a known shelf entry with nothing queued is up to date; otherwise nothing has gone yet.
 */
internal fun bookSyncOf(
    link: HardcoverBookLink,
    pending: Long,
): HardcoverBookSync =
    when {
        link.suppressedListenThrough != null -> HardcoverBookSync.REMOVED_ON_HARDCOVER
        pending > 0 -> HardcoverBookSync.WAITING
        link.hcUserBookId != null -> HardcoverBookSync.UP_TO_DATE
        else -> HardcoverBookSync.NOTHING_SENT_YET
    }
