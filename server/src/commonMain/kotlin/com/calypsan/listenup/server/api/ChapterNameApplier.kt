package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.MetadataService

/**
 * Applies Audible chapter *names* to an existing book aggregate, by ordinal.
 *
 * Reads the book's chapters and the Audible chapter list for [asin]/[region],
 * sorts both by start time, and — only when the counts match — overwrites the
 * `title` of each chapter whose ordinal is in `ordinals`, preserving every
 * chapter's `startTime`/`duration`. The mutated aggregate is written through
 * [BookRepository.upsert], so the revision bumps and an SSE event is published.
 *
 * Returns [MetadataError.NotFound] when the book is absent or Audible has no
 * chapters, and [MetadataError.ChapterCountMismatch] when the two chapter counts
 * differ (a different edition) — writing nothing in either failure case. An
 * empty `ordinals` set is a no-op success.
 */
internal class ChapterNameApplier(
    private val bookRepository: BookRepository,
    private val metadataService: MetadataService,
) {
    suspend fun apply(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
        ordinals: Set<Int>,
    ): AppResult<Unit> {
        val existing =
            bookRepository.findById(bookId)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "Book ${bookId.value} not found in the database."),
                )

        return metadataService.getBookChapters(region, asin).flatMap { audibleChapters ->
            if (audibleChapters.isEmpty()) {
                return@flatMap AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "No Audible chapters for ASIN $asin in region $region."),
                )
            }

            val local = existing.chapters.sortedBy { it.startTime }
            val remote = audibleChapters.sortedBy { it.startMs }
            if (local.size != remote.size) {
                return@flatMap AppResult.Failure(
                    MetadataError.ChapterCountMismatch(
                        debugInfo = "Local ${local.size} vs Audible ${remote.size} chapters for ASIN $asin.",
                    ),
                )
            }

            if (ordinals.isEmpty()) return@flatMap AppResult.Success(Unit)

            val renamed =
                local.mapIndexed { ordinal, chapter ->
                    if (ordinal in ordinals) chapter.copy(title = remote[ordinal].title) else chapter
                }
            bookRepository.upsert(existing.copy(chapters = renamed), clientOpId = null).map { }
        }
    }
}
