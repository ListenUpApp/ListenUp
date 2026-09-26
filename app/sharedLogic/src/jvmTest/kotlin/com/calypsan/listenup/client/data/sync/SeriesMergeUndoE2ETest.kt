package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private const val TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e for series merge undo: the server revives the merged-away series and moves its books
 * back; the firehose must carry the revival (an `Updated` for a row the client holds as a
 * tombstone) and every re-upserted book into client Room. Server-side undo semantics live in
 * `:server`'s `SeriesMergeUndoTest`.
 */
class SeriesMergeUndoE2ETest :
    FunSpec({

        test("an undone series merge revives the series and its books in client Room") {
            withClientSyncEngineAgainstServer {
                val source = serverSeriesRepository.resolveOrCreate("The Dark Tower (Bachman)")
                val target = serverSeriesRepository.resolveOrCreate("The Dark Tower")
                engine.start(currentUserId = "u1")
                serverBookRepository.upsert(
                    bookInSeries(id = "undo-b1", series = BookSeriesPayload(source.value, "The Dark Tower (Bachman)", 1.0)),
                )
                waitUntil { "undo-b1" in clientDatabase.seriesDao().getBookIdsForSeries(source.value) }

                seriesEditRepository
                    .mergeSeries(SeriesId(source.value), SeriesId(target.value))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                waitUntil { clientDatabase.seriesDao().getById(source.value)?.deletedAt != null }

                val receipt =
                    serverSeriesService
                        .listMergeReceipts(target)
                        .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                        .data
                        .single()
                        .id
                serverSeriesService.undoSeriesMerge(receipt).shouldBeInstanceOf<AppResult.Success<*>>()

                // seriesDao().getById does NOT filter tombstones (unlike the genre DAO), so the
                // revival is read off deletedAt.
                waitUntil {
                    val revived = clientDatabase.seriesDao().getById(source.value)
                    revived != null && revived.deletedAt == null &&
                        "undo-b1" in clientDatabase.seriesDao().getBookIdsForSeries(source.value) &&
                        "undo-b1" !in clientDatabase.seriesDao().getBookIdsForSeries(target.value)
                }
            }
        }
    })

private suspend fun waitUntil(predicate: suspend () -> Boolean) {
    withTimeout(TIMEOUT_SECONDS.seconds) {
        while (!predicate()) delay(50)
    }
}

/** A minimal server book payload belonging to exactly [series] — mirrors `SeriesMergeE2ETest`'s fixture. */
private fun bookInSeries(
    id: String,
    series: BookSeriesPayload,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = id,
        sortTitle = id,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1L,
        contributors = emptyList(),
        series = listOf(series),
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
