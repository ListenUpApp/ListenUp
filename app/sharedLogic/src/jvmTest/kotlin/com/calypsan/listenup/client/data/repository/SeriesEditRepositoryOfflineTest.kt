package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SeriesEditRepositoryOfflineTest :
    FunSpec({
        test("offline series edit persists to Room and enqueues a pending op without ServerConnectError") {
            runTest {
                val db = createInMemoryTestDatabase()
                val seriesId = SeriesId("series1")
                db.seriesDao().upsert(
                    SeriesEntity(
                        id = seriesId,
                        name = "Old Name",
                        description = null,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                )

                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    }
                val offlineEditor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                val repo =
                    SeriesEditRepositoryImpl(
                        channel = RpcChannel.forTest(mock<SeriesService>()),
                        seriesDao = db.seriesDao(),
                        offlineEditor = offlineEditor,
                    )

                val result = repo.updateSeries(seriesId, SeriesUpdate(name = "New Name"))

                result shouldBe AppResult.Success(Unit)
                db.seriesDao().getById(seriesId.value)?.name shouldBe "New Name"
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "series"
                db.close()
            }
        }

        test("deleteSeries soft-deletes the series, cascade-removes book_series, and enqueues a series delete op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val seriesId = SeriesId("series1")
                db.seriesDao().upsert(
                    SeriesEntity(
                        id = seriesId,
                        name = "Mistborn",
                        description = null,
                        revision = 2,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                )
                // book_series carries FK constraints to books + series, so both parents must exist.
                db.seedSeriesTestBook(BookId("b1"))
                db.bookSeriesDao().insert(BookSeriesCrossRef(bookId = BookId("b1"), seriesId = seriesId, sequence = "1"))

                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val offlineEditor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner =
                            object : TransactionRunner {
                                override suspend fun <R> atomically(block: suspend () -> R): R = block()
                            },
                        authSession = FakeAuthSession(userId = "u1"),
                    )
                val repo =
                    SeriesEditRepositoryImpl(
                        channel = RpcChannel.forTest(mock<SeriesService>()),
                        seriesDao = db.seriesDao(),
                        offlineEditor = offlineEditor,
                    )

                val result = repo.deleteSeries(seriesId)

                result shouldBe AppResult.Success(Unit)
                // Series tombstoned (getById returns the row with deletedAt set) and membership gone.
                db
                    .seriesDao()
                    .getById(seriesId.value)
                    ?.deletedAt
                    .shouldNotBeNull()
                db.seriesDao().getBookIdsForSeries(seriesId.value).shouldBeEmpty()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "series"
                op.entityId shouldBe "series1"
                op.opType shouldBe "delete"
                db.close()
            }
        }
    })

/** Seed a minimal live book so `book_series` FK inserts have a parent row. */
private suspend fun ListenUpDatabase.seedSeriesTestBook(id: BookId) =
    bookDao().upsert(
        BookEntity(
            id = id,
            libraryId = LibraryId("lib1"),
            folderId = FolderId("folder1"),
            title = "Book ${id.value}",
            totalDuration = 3_600_000L,
            createdAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        ),
    )
