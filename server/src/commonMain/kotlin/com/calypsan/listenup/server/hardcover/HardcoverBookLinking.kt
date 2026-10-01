package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.BookAccessPolicy

/**
 * The manual side of matching (spec B4), behind [com.calypsan.listenup.api.HardcoverService]:
 * search Hardcover's catalog, link a book to the user's pick (which unparks its waiting pushes), and
 * unlink it for "Change match". Search costs one Hardcover `search` plus one lookup for the editions,
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
        rateLimiter.await()
        val hits = graphQl.searchBooks(token, trimmed).valueOr { return it.toFailure("searchBooks") }
        if (hits.isEmpty()) return AppResult.Success(emptyList())
        rateLimiter.await()
        val books =
            graphQl
                .booksByIds(
                    token,
                    hits.map {
                        it.bookId
                    },
                ).valueOr { return it.toFailure("booksByIds") }
                .associateBy { it.id }
        return AppResult.Success(
            hits.map { hit ->
                val book = books[hit.bookId]
                HardcoverBookCandidate(
                    hcBookId = hit.bookId,
                    hcEditionId = book?.defaultAudioEditionId,
                    title = book?.title ?: hit.title,
                    authors = book?.authors?.takeIf { it.isNotEmpty() } ?: hit.authors,
                    releaseYear = book?.releaseYear ?: hit.releaseYear,
                )
            },
        )
    }

    /** Links [bookId] to [hcBookId] (as [hcEditionId]) for [userId], and sends the pushes that were waiting. */
    suspend fun link(
        userId: String,
        role: UserRole,
        bookId: String,
        hcBookId: Long,
        hcEditionId: Long?,
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
        links.linkManually(userId, bookId, hcBookId, hcEditionId)
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
