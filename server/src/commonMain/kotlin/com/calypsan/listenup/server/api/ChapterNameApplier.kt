package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.services.BookRepository

/**
 * Applies composed chapter *names* to an existing book aggregate, by ordinal.
 *
 * Reads the book's chapters and the composed chapter list for [asin]/[locale] — resolved
 * across the provider registry through [EnrichmentCoordinator.composeChapters], the *same*
 * composition the preview ([MetadataLookupServiceImpl.getBookChapters]) shows, so preview and
 * apply can never disagree on which provider won. It sorts both by start time and — only when
 * the counts match — overwrites the `title` of each chapter whose ordinal is in `ordinals` with
 * the composed name (skipping unnamed markers), preserving every chapter's `startTime`/`duration`.
 *
 * The mutated aggregate is written through [BookRepository.upsert] stamped
 * [ChapterSource.USER] — applying names is an explicit user choice (selection-as-consent), so
 * the result is rescan-protected exactly like a hand edit and does not revert on the next scan.
 * The revision bumps and an SSE event is published.
 *
 * Returns [MetadataError.NotFound] when the book is absent or no provider has chapters, and
 * [MetadataError.ChapterCountMismatch] when the two chapter counts differ (a different edition)
 * — writing nothing in either failure case. An empty `ordinals` set is a no-op success.
 */
internal class ChapterNameApplier(
    private val bookRepository: BookRepository,
    private val coordinator: EnrichmentCoordinator,
) {
    suspend fun apply(
        bookId: BookId,
        asin: String,
        locale: MetadataLocale,
        ordinals: Set<Int>,
    ): AppResult<Unit> {
        val existing =
            bookRepository.findById(bookId)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "Book ${bookId.value} not found in the database."),
                )

        val composed =
            coordinator.composeChapters(BookIdentity(asin = asin, title = ""), locale)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(
                        debugInfo = "No composed chapters for ASIN $asin in region ${locale.region}.",
                    ),
                )

        val local = existing.chapters.sortedBy { it.startTime }
        val remote = composed.chapters.sortedBy { it.startMs }
        if (local.size != remote.size) {
            return AppResult.Failure(
                MetadataError.ChapterCountMismatch(
                    debugInfo = "Local ${local.size} vs composed ${remote.size} chapters for ASIN $asin.",
                ),
            )
        }

        if (ordinals.isEmpty()) return AppResult.Success(Unit)

        val renamed =
            local.mapIndexed { ordinal, chapter ->
                val name = remote[ordinal].title?.takeIf { it.isNotBlank() }
                if (ordinal in ordinals && name != null) chapter.copy(title = name) else chapter
            }
        return bookRepository
            .upsert(existing.copy(chapters = renamed, chapterSource = ChapterSource.USER), clientOpId = null)
            .map { }
    }
}
