package com.calypsan.listenup.client.data.repository

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * A shelf tile (its cover mosaic and its length) is a read of the `books` table as well as `shelf_books`, so it
 * follows its books: a cover or duration written later reaches the tile, and a membership that synced before its book
 * fills in when the book lands.
 */
class ShelfTilesFollowBooksTest :
    FunSpec({
        test("a book's new cover and another's new duration reach the shelf tile") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    seedShelf(db, "s1", books = listOf("b1", "b2"))
                    seedBook(db, "b1", coverHash = "old", durationMs = 1_000L)
                    seedBook(db, "b2", coverHash = "h2", durationMs = 2_000L)

                    shelfRepo(db).observeMyShelves("u1").test {
                        val first = awaitItem().single()
                        first.covers.map { it.coverHash } shouldBe listOf("old", "h2")
                        first.totalDurationSeconds shouldBe 3L

                        seedBook(db, "b1", coverHash = "new", coverDownloadedAt = 5L, durationMs = 1_000L)
                        awaitShelf { it.covers.first().coverHash == "new" }.covers.first() shouldBe
                            ShelfCover(bookId = "b1", coverPath = "/covers/b1.jpg", coverHash = "new")

                        seedBook(db, "b2", coverHash = "h2", durationMs = 12_000L)
                        awaitShelf { it.totalDurationSeconds == 13L }.totalDurationSeconds shouldBe 13L
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    db.close()
                }
            }
        }

        test("a membership that synced before its book fills in when the book lands") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    seedShelf(db, "s1", books = listOf("b1"))

                    shelfRepo(db).observeMyShelves("u1").test {
                        val blank = awaitItem().single()
                        blank.covers shouldBe emptyList()
                        blank.totalDurationSeconds shouldBe 0L

                        seedBook(db, "b1", coverHash = "h1", durationMs = 4_000L)
                        val filled = awaitShelf { it.covers.isNotEmpty() }
                        filled.covers.map { it.bookId } shouldBe listOf("b1")
                        filled.totalDurationSeconds shouldBe 4L
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    db.close()
                }
            }
        }

        test("observeById follows its books the same way") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    seedShelf(db, "s1", books = listOf("b1"))
                    seedBook(db, "b1", coverHash = "old", durationMs = 1_000L)

                    shelfRepo(db).observeById(ShelfId("s1")).test {
                        awaitItem()!!.covers.map { it.coverHash } shouldBe listOf("old")
                        seedBook(db, "b1", coverHash = "new", durationMs = 9_000L)
                        var latest = awaitItem()
                        while (latest?.covers?.firstOrNull()?.coverHash != "new") latest = awaitItem()
                        latest.totalDurationSeconds shouldBe 9L
                        cancelAndIgnoreRemainingEvents()
                    }
                } finally {
                    db.close()
                }
            }
        }
    })

/** The next emission satisfying [predicate]; the tile may re-emit unchanged between writes. */
private suspend fun ReceiveTurbine<List<Shelf>>.awaitShelf(predicate: (Shelf) -> Boolean): Shelf {
    while (true) {
        val shelf = awaitItem().single()
        if (predicate(shelf)) return shelf
    }
}

private suspend fun seedShelf(
    db: ListenUpDatabase,
    id: String,
    books: List<String>,
) {
    db.shelfDao().upsert(
        ShelfEntity(
            id = id,
            name = "Reading",
            description = "",
            isPrivate = false,
            revision = 1,
            deletedAt = null,
            updatedAt = 100L,
            createdAt = 50L,
        ),
    )
    db.shelfBookDao().upsertAll(
        books.mapIndexed { index, bookId ->
            ShelfBookEntity(
                id = "$id:$bookId",
                shelfId = id,
                bookId = bookId,
                sortOrder = index,
                revision = 1,
                deletedAt = null,
                updatedAt = 100L,
                createdAt = 50L,
            )
        },
    )
}

private suspend fun seedBook(
    db: ListenUpDatabase,
    id: String,
    coverHash: String?,
    durationMs: Long,
    coverDownloadedAt: Long? = null,
) {
    db.bookDao().upsert(
        BookEntity(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Book $id",
            sortTitle = "Book $id",
            subtitle = null,
            coverHash = coverHash,
            coverDownloadedAt = coverDownloadedAt?.let(::Timestamp),
            totalDuration = durationMs,
            description = null,
            publishYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
        ),
    )
}

private fun shelfRepo(db: ListenUpDatabase): ShelfRepositoryImpl {
    val userDao = mock<UserDao> { everySuspend { getCurrentUser() } returns null }
    val imageStorage: ImageStorage = mock { every { getCoverPath(any()) } calls { (id: BookId) -> "/covers/${id.value}.jpg" } }
    return ShelfRepositoryImpl(
        dao = db.shelfDao(),
        shelfBookDao = db.shelfBookDao(),
        userDao = userDao,
        channel = RpcChannel.forTest(mock<ShelfService>()),
        offlineEditor = noopOfflineEditor(),
        collectionBookDao = db.collectionBookDao(),
        imageStorage = imageStorage,
    )
}
