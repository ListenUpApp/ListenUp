package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
private const val SOURCE_SERIES_NAME = "The Dark Tower (Bachman)"
private const val TARGET_SERIES_NAME = "The Dark Tower"
private const val BOOK_ONE_ID = "series-merge-b1"
private const val BOOK_TWO_ID = "series-merge-b2"

/**
 * Tier 3 e2e test for the Books-C2 series merge: a client-side call to
 * [com.calypsan.listenup.client.domain.repository.SeriesEditRepository.mergeSeries]
 * crosses the live kotlinx.rpc transport into the in-process `:server`'s
 * `SeriesService`. The server relinks every `book_series_memberships` junction row
 * referencing the source onto the target, re-upserts every affected book to bump
 * revisions, soft-deletes the source, and emits a burst of SSE events: one
 * `books.Updated` per affected book + one `series.Deleted` for the source tombstone.
 *
 * All of these must land in client Room: every affected book's `book_series`
 * junction points at the target id (the [BookMirrorApply.applySeries]
 * path replaces the junction set on every book upsert), and the source row carries
 * `deletedAt != null`. The poll witness combines both signals so the cascade is
 * only fully applied when SSE has delivered every event.
 *
 * Series merge has NO `creditedAs` equivalent — series merges fully rewrite
 * memberships onto the target (per the Books-C2 spec's locked decision).
 *
 * Server-side merge semantics (idempotency, self-target rejection, source-tombstoned
 * rejection, FTS reindex of `book_search`) are covered by `:server`'s
 * `SeriesServiceImplMergeTest`. This file proves the cross-domain wiring survives
 * the full RPC → SSE → Room round trip.
 */
class SeriesMergeE2ETest :
    FunSpec({

        test(
            "mergeSeries cascade: junction relink to target + source tombstone all land in client Room",
        ) {
            withClientSyncEngineAgainstServer {
                // Seed source + target series. resolveOrCreate publishes a series.Created SSE
                // event per call that the engine catches up on after start.
                val sourceId = serverSeriesRepository.resolveOrCreate(SOURCE_SERIES_NAME)
                val targetId = serverSeriesRepository.resolveOrCreate(TARGET_SERIES_NAME)

                engine.start(currentUserId = "u1")

                // Both books linked to source.
                serverBookRepository.upsert(
                    bookFixtureInSeries(
                        id = BOOK_ONE_ID,
                        title = "The Gunslinger",
                        seriesId = sourceId.value,
                        seriesName = SOURCE_SERIES_NAME,
                        sequence = "1",
                    ),
                )
                serverBookRepository.upsert(
                    bookFixtureInSeries(
                        id = BOOK_TWO_ID,
                        title = "The Drawing of the Three",
                        seriesId = sourceId.value,
                        seriesName = SOURCE_SERIES_NAME,
                        sequence = "2",
                    ),
                )

                // Wait for the seed catch-up: every book is linked to source through Room.
                // Until both junctions are present, the post-merge relink assertion is ambiguous.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (!bothBooksLinkedToSeries(
                            clientDb = clientDatabase,
                            seriesId = sourceId.value,
                        )
                    ) {
                        // SSE delivery latency is non-deterministic; poll the real query.
                    }
                }

                // Issue the merge over the real kotlinx.rpc transport.
                val result =
                    seriesEditRepository.mergeSeries(
                        source = SeriesId(sourceId.value),
                        target = SeriesId(targetId.value),
                    )
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Merge complete only when BOTH of these hold in client Room:
                //  - both books' junctions point at target (via the book_series witness),
                //  - source row is tombstoned (from the series.Deleted event).
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (!mergeFullyLanded(
                            clientDb = clientDatabase,
                            sourceId = sourceId.value,
                            targetId = targetId.value,
                        )
                    ) {
                        // SSE delivery latency is non-deterministic; poll until convergence.
                    }
                }

                // Dual assertion against the server side — proves the server is the
                // one driving the client state, not a quirk of local handler logic.
                serverSeriesRepository
                    .findById(sourceId.value)
                    .shouldNotBeNull()
                    .deletedAt
                    .shouldNotBeNull()

                val finalBookOne = serverBookRepository.findById(BookId(BOOK_ONE_ID)).shouldNotBeNull()
                val finalBookTwo = serverBookRepository.findById(BookId(BOOK_TWO_ID)).shouldNotBeNull()
                finalBookOne.series.map { it.id } shouldContain targetId.value
                finalBookOne.series.map { it.id } shouldNotContain sourceId.value
                finalBookTwo.series.map { it.id } shouldContain targetId.value
                finalBookTwo.series.map { it.id } shouldNotContain sourceId.value
            }
        }
    })

/** True once both seed books have a `book_series` junction row to [seriesId]. */
private suspend fun bothBooksLinkedToSeries(
    clientDb: ListenUpDatabase,
    seriesId: String,
): Boolean {
    val booksInSeries = clientDb.seriesDao().getBookIdsForSeries(seriesId)
    return BOOK_ONE_ID in booksInSeries && BOOK_TWO_ID in booksInSeries
}

/**
 * True once every signal of a fully-applied series merge is observable in client Room:
 * both affected books' junctions point at [targetId] (and no longer reference [sourceId]),
 * and the source row is tombstoned.
 */
private suspend fun mergeFullyLanded(
    clientDb: ListenUpDatabase,
    sourceId: String,
    targetId: String,
): Boolean {
    val targetMembers = clientDb.seriesDao().getBookIdsForSeries(targetId)
    val sourceMembers = clientDb.seriesDao().getBookIdsForSeries(sourceId)
    val bothOnTarget = BOOK_ONE_ID in targetMembers && BOOK_TWO_ID in targetMembers
    val neitherOnSource = BOOK_ONE_ID !in sourceMembers && BOOK_TWO_ID !in sourceMembers
    val sourceTombstoned = clientDb.seriesDao().getById(sourceId)?.deletedAt != null
    return bothOnTarget && neitherOnSource && sourceTombstoned
}

private fun bookFixtureInSeries(
    id: String,
    title: String,
    seriesId: String,
    seriesName: String,
    sequence: String?,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
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
        series = listOf(BookSeriesPayload(id = seriesId, name = seriesName, sequence = sequence)),
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
