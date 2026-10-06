package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.matching.review.BookReviewer
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import kotlinx.coroutines.CancellationException

private val log = loggerFor<BookMatchApplier>()

/**
 * Match details' Apply (spec, *Apply: one transaction*): re-derive the Review, plan the person's choices against
 * it, resolve names and fetch the cover, then write everything in one transaction with a receipt. Nothing is
 * written on any failure before the commit. After it, best effort, the book's outside ratings are refreshed.
 */
internal class BookMatchApplier(
    private val reviewer: BookReviewer,
    private val coordinator: EnrichmentCoordinator,
    private val preparer: MatchPreparer,
    private val writer: BookMatchWriter,
    private val ratingsRefresh: (suspend (BookId, MetadataLocale) -> Unit)? = null,
) {
    suspend fun apply(
        book: BookSyncPayload,
        request: BookMatchApply,
        locale: MetadataLocale,
        appliedBy: String,
    ): AppResult<MatchReceipt> {
        val model =
            when (val reviewed = reviewer.review(book, request.candidate, locale)) {
                is AppResult.Success -> reviewed.data
                is AppResult.Failure -> return reviewed
            }
        val ladders =
            if (request.genres.add.isNotEmpty()) coordinator.composeGenreLadders(model.identity, locale) else emptyList()
        val draft =
            when (val planned = MatchPlanner.plan(model, request, ladders)) {
                is AppResult.Success -> planned.data
                is AppResult.Failure -> return planned
            }
        val plan =
            when (val prepared = preparer.prepare(draft, book, model.currentMoods)) {
                is AppResult.Success -> prepared.data
                is AppResult.Failure -> return prepared
            }
        val written = writer.write(plan, basedOnRevision = request.basedOnRevision, appliedBy = appliedBy)
        if (written is AppResult.Success) refreshRatings(BookId(book.id), locale)
        return written
    }

    private suspend fun refreshRatings(
        bookId: BookId,
        locale: MetadataLocale,
    ) {
        val refresh = ratingsRefresh ?: return
        try {
            refresh(bookId, locale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "External rating fetch failed for ${bookId.value} after a match — skipping" }
        }
    }
}
