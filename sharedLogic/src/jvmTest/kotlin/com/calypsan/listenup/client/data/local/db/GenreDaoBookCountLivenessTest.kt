package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Liveness tests for [GenreDao.observeAllGenresWithBookCount].
 *
 * The per-genre book count must reflect only live (non-tombstoned) books.
 * When a book is server-tombstoned via [BookDao.softDelete], its `book_genres`
 * junction rows are deliberately retained (a revived book gets its genres back
 * without a full aggregate re-send), so the count query is the sole place that
 * must honour liveness.
 */
class GenreDaoBookCountLivenessTest :
    FunSpec({

        test("tombstoning a book removes it from the genre book count") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val genreDao = db.genreDao()

                    seedBook(bookDao, "A")
                    seedGenre(genreDao, id = "g-scifi", name = "scifi", path = "/scifi")
                    genreDao.insertAllBookGenres(
                        listOf(BookGenreCrossRef(bookId = BookId("A"), genreId = "g-scifi")),
                    )

                    genreDao
                        .observeAllGenresWithBookCount()
                        .first()
                        .single { it.genre.id == "g-scifi" }
                        .bookCount shouldBe 1

                    // Apply a server tombstone exactly as BookMirrorApply.tombstoneById does:
                    // soft-delete the book; the book_genres junction row survives.
                    bookDao.softDelete(id = BookId("A"), deletedAt = 2L, revision = 2L)

                    genreDao
                        .observeAllGenresWithBookCount()
                        .first()
                        .single { it.genre.id == "g-scifi" }
                        .bookCount shouldBe 0
                }
            } finally {
                db.close()
            }
        }

        test("count reflects only live books when a genre has both live and tombstoned books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val genreDao = db.genreDao()

                    seedBook(bookDao, "A")
                    seedBook(bookDao, "B")
                    seedGenre(genreDao, id = "g-scifi", name = "scifi", path = "/scifi")
                    genreDao.insertAllBookGenres(
                        listOf(
                            BookGenreCrossRef(bookId = BookId("A"), genreId = "g-scifi"),
                            BookGenreCrossRef(bookId = BookId("B"), genreId = "g-scifi"),
                        ),
                    )

                    bookDao.softDelete(id = BookId("B"), deletedAt = 2L, revision = 2L)

                    genreDao
                        .observeAllGenresWithBookCount()
                        .first()
                        .single { it.genre.id == "g-scifi" }
                        .bookCount shouldBe 1
                }
            } finally {
                db.close()
            }
        }

        test("a live genre with no junction rows still appears with a zero count") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val genreDao = db.genreDao()

                    seedGenre(genreDao, id = "g-empty", name = "empty", path = "/empty")

                    genreDao
                        .observeAllGenresWithBookCount()
                        .first()
                        .single { it.genre.id == "g-empty" }
                        .bookCount shouldBe 0
                }
            } finally {
                db.close()
            }
        }
    })

private suspend fun seedBook(
    bookDao: BookDao,
    id: String,
) {
    bookDao.upsert(
        BookEntity(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Book $id",
            sortTitle = null,
            subtitle = null,
            coverHash = null,
            coverBlurHash = null,
            totalDuration = 0L,
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

private suspend fun seedGenre(
    genreDao: GenreDao,
    id: String,
    name: String,
    path: String,
) {
    genreDao.upsert(
        GenreEntity(
            id = id,
            name = name,
            slug = id,
            path = path,
            parentId = null,
            depth = 0,
            sortOrder = 0,
        ),
    )
}
