package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [GenreDao.getGenresForBooks].
 *
 * Verifies the bulk genre lookup that backs the home screen stats genre breakdown.
 */
class GenreDaoGetGenresForBooksTest :
    FunSpec({

        test("returns correct genre names for two books with disjoint and shared genres") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val genreDao = db.genreDao()

                    seedBook(bookDao, "A")
                    seedBook(bookDao, "B")
                    seedGenre(genreDao, id = "g-scifi", name = "scifi", path = "/scifi")
                    seedGenre(genreDao, id = "g-fantasy", name = "fantasy", path = "/fantasy")

                    // Book A → scifi
                    genreDao.insertAllBookGenres(
                        listOf(BookGenreCrossRef(bookId = BookId("A"), genreId = "g-scifi")),
                    )
                    // Book B → scifi + fantasy
                    genreDao.insertAllBookGenres(
                        listOf(
                            BookGenreCrossRef(bookId = BookId("B"), genreId = "g-scifi"),
                            BookGenreCrossRef(bookId = BookId("B"), genreId = "g-fantasy"),
                        ),
                    )

                    val result = genreDao.getGenresForBooks(setOf("A", "B"))

                    result shouldHaveSize 2
                    result["A"]?.toSet() shouldBe setOf("scifi")
                    result["B"]?.toSet() shouldBe setOf("scifi", "fantasy")
                }
            } finally {
                db.close()
            }
        }

        test("a book with no genres is excluded from the returned map") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val genreDao = db.genreDao()

                    seedBook(bookDao, "A")
                    seedBook(bookDao, "B")
                    seedGenre(genreDao, id = "g-scifi", name = "scifi", path = "/scifi")

                    genreDao.insertAllBookGenres(
                        listOf(BookGenreCrossRef(bookId = BookId("A"), genreId = "g-scifi")),
                    )
                    // Book B has no genres

                    val result = genreDao.getGenresForBooks(setOf("A", "B"))

                    result shouldHaveSize 1
                    result["A"] shouldNotBe null
                    result["B"] shouldBe null
                }
            } finally {
                db.close()
            }
        }

        test("an unknown book ID is excluded from the returned map") {
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

                    val result = genreDao.getGenresForBooks(setOf("A", "UNKNOWN"))

                    result shouldHaveSize 1
                    result["UNKNOWN"] shouldBe null
                }
            } finally {
                db.close()
            }
        }

        test("empty input set returns an empty map") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.genreDao().getGenresForBooks(emptySet()).shouldBeEmpty()
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
