package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookGenreCrossRef
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
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
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Offline-first contract for the genre write surface. `updateGenre` and `deleteGenre` must write Room
 * and enqueue a durable outbox op with no server present — never fail with a
 * [com.calypsan.listenup.api.error.ServerConnectError]. `deleteGenre` mirrors the server's "no live
 * descendants" precondition locally (fail with nothing written), and cascade-removes the genre's
 * `book_genres` links. `createGenre`/`moveGenre`/`mergeGenres` stay online (covered elsewhere).
 */
class GenreRepositoryOfflineTest :
    FunSpec({
        fun fixture(): GenreOfflineFixture {
            val db = createInMemoryTestDatabase()
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
            return GenreOfflineFixture(db, offlineEditor)
        }

        test("updateGenre persists name/sortOrder to Room and enqueues a genres op keyed by genreId") {
            runTest {
                val f = fixture()
                f.db.genreDao().upsert(
                    GenreEntity(id = "g1", name = "Sci Fi", slug = "sci-fi", path = "/sci-fi", sortOrder = 3, revision = 4),
                )
                val repo = f.repo(mock())

                val result = repo.updateGenre(GenreId("g1"), GenreUpdate(name = "Science Fiction", sortOrder = 9))

                result shouldBe AppResult.Success(Unit)
                val row = f.db.genreDao().getById("g1")
                row?.name shouldBe "Science Fiction"
                row?.sortOrder shouldBe 9
                // Slug and revision are left untouched — the echo is the final word.
                row?.slug shouldBe "sci-fi"
                row?.revision shouldBe 4
                val op =
                    f.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "genres"
                op.entityId shouldBe "g1"
                op.opType shouldBe "update"
                f.db.close()
            }
        }

        test("deleteGenre soft-deletes the genre, cascade-removes its book_genres, and enqueues a genres delete op") {
            runTest {
                val f = fixture()
                f.db.genreDao().upsert(GenreEntity(id = "g1", name = "Sci Fi", slug = "sci-fi", path = "/sci-fi", revision = 2))
                // book_genres carries FK constraints to books + genres, so both parents must exist.
                f.db.seedGenreTestBook(BookId("b1"))
                f.db.seedGenreTestBook(BookId("b2"))
                f.db.genreDao().insertAllBookGenres(
                    listOf(
                        BookGenreCrossRef(bookId = BookId("b1"), genreId = "g1"),
                        BookGenreCrossRef(bookId = BookId("b2"), genreId = "g1"),
                    ),
                )
                val repo = f.repo(mock())

                val result = repo.deleteGenre(GenreId("g1"))

                result shouldBe AppResult.Success(Unit)
                // Genre tombstoned (getById excludes tombstones) and both junctions gone.
                f.db
                    .genreDao()
                    .getById("g1")
                    .shouldBeNull()
                f.db
                    .genreDao()
                    .getBookIdsForGenre("g1")
                    .shouldBeEmpty()
                val op =
                    f.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "genres"
                op.entityId shouldBe "g1"
                op.opType shouldBe "delete"
                f.db.close()
            }
        }

        test("deleteGenre with a live child fails with HasDescendants and writes/enqueues nothing") {
            runTest {
                val f = fixture()
                f.db.genreDao().upsert(GenreEntity(id = "g1", name = "Fiction", slug = "fiction", path = "/fiction", revision = 1))
                f.db.genreDao().upsert(
                    GenreEntity(id = "g2", name = "Sci Fi", slug = "sci-fi", path = "/fiction/sci-fi", parentId = "g1", revision = 1),
                )
                val repo = f.repo(mock())

                val result = repo.deleteGenre(GenreId("g1"))

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<GenreError.HasDescendants>()
                // Parent still live, nothing enqueued.
                f.db
                    .genreDao()
                    .getById("g1")
                    .shouldNotBeNull()
                f.db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .shouldBeEmpty()
                f.db.close()
            }
        }
    })

private class GenreOfflineFixture(
    val db: ListenUpDatabase,
    private val offlineEditor: OfflineEditor,
) {
    fun repo(service: GenreService): GenreRepositoryImpl =
        GenreRepositoryImpl(
            dao = db.genreDao(),
            channel = RpcChannel.forTest(service),
            offlineEditor = offlineEditor,
        )
}

/** Seed a minimal live book so `book_genres` FK inserts have a parent row. */
private suspend fun ListenUpDatabase.seedGenreTestBook(id: BookId) =
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
