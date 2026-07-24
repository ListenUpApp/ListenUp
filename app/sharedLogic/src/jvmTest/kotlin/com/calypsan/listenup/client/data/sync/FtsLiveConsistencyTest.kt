package com.calypsan.listenup.client.data.sync

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Plan 008 — offline FTS stays consistent after live edits.
 *  - [FtsPopulator.observeContentChanges] fires on any searchable-content write, so the live SSE
 *    apply drives a debounced reindex (a book edited on another device becomes searchable offline
 *    without a scan/resync).
 *  - [FtsPopulator.refreshSince] self-heals a per-row insert failure (retry → rebuild), so a
 *    transient FTS write error can't leave a book permanently missing from search (FTS-2).
 */
class FtsLiveConsistencyTest :
    FunSpec({

        fun buildPopulator(
            db: ListenUpDatabase,
            searchDao: SearchDao = db.searchDao(),
        ) = FtsPopulator(
            bookDao = db.bookDao(),
            contributorDao = db.contributorDao(),
            seriesDao = db.seriesDao(),
            searchDao = searchDao,
            transactionRunner = RoomTransactionRunner(db),
        )

        suspend fun seedBook(
            db: ListenUpDatabase,
            id: String,
            title: String,
            revision: Long,
        ) {
            db.bookDao().upsert(
                BookEntity(
                    id = BookId(id),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = title,
                    sortTitle = title,
                    subtitle = null,
                    coverHash = null,
                    totalDuration = 3_600_000L,
                    description = null,
                    revision = revision,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
        }

        // Real Room's InvalidationTracker delivers on its own dispatcher, so this runs on the real
        // clock (no runTest) — Turbine's timeout catches a missed emission.
        test("observeContentChanges fires when searchable content is written to Room") {
            val db = createInMemoryTestDatabase()
            try {
                val populator = buildPopulator(db)
                populator.observeContentChanges().test {
                    awaitItem() // initial replay — current (empty) state
                    seedBook(db, id = "b1", title = "New Arrival", revision = 1)
                    awaitItem() // the books write invalidates the query → re-emits
                    cancelAndConsumeRemainingEvents()
                }
            } finally {
                db.close()
            }
        }

        test("refreshSince self-heals a per-row FTS insert failure — the book is not left out of search") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Healme Book", revision = 1)
                    // insertBookFts throws on its FIRST call (a transient write failure), succeeds after.
                    val flaky = ThrowOnceOnInsertSearchDao(db.searchDao())
                    val populator = buildPopulator(db, searchDao = flaky)

                    // b1's revision (1) is above the zero watermark → reindexed. The first insert throws
                    // and is collected; the self-heal retry re-inserts it instead of stranding it.
                    populator.refreshSince(SearchIndexWatermark(0L, 0L, 0L, 0L))

                    flaky.insertAttempts shouldBe 2
                    db.searchDao().searchBooks("Healme*").isNotEmpty() shouldBe true
                }
            } finally {
                db.close()
            }
        }
    })

/** Delegates every [SearchDao] call to [delegate], but throws on the FIRST `insertBookFts` to
 *  simulate a transient FTS write failure. */
private class ThrowOnceOnInsertSearchDao(
    private val delegate: SearchDao,
) : SearchDao by delegate {
    var insertAttempts = 0
        private set

    override suspend fun insertBookFts(
        bookId: String,
        title: String,
        subtitle: String?,
        description: String?,
        author: String?,
        narrator: String?,
        seriesName: String?,
        genres: String?,
    ) {
        insertAttempts++
        if (insertAttempts == 1) error("simulated transient FTS insert failure")
        delegate.insertBookFts(bookId, title, subtitle, description, author, narrator, seriesName, genres)
    }
}
