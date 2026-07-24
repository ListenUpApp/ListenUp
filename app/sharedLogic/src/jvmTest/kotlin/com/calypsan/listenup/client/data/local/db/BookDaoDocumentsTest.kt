package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.test.runTest

/**
 * Pins that [BookDao.observeBookIdsWithDocuments] returns only the ids of books that have at
 * least one row in [book_documents].
 */
class BookDaoDocumentsTest :
    FunSpec({

        test("observeBookIdsWithDocuments returns only book ids that have a document row") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val documentDao = db.bookDocumentDao()

                    // Seed two books.
                    daoDocsSeedBook(bookDao, "b1")
                    daoDocsSeedBook(bookDao, "b2")

                    // Give only b1 a document.
                    documentDao.upsertAll(
                        listOf(
                            BookDocumentEntity(
                                bookId = BookId("b1"),
                                index = 0,
                                id = "doc-1",
                                filename = "companion.pdf",
                                format = "pdf",
                                size = 1024L,
                                hash = "abc123",
                            ),
                        ),
                    )

                    bookDao.observeBookIdsWithDocuments().test {
                        val ids = awaitItem()
                        ids shouldContainExactly listOf("b1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeBookIdsWithDocuments emits empty list when no documents exist") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()

                    daoDocsSeedBook(bookDao, "b1")
                    daoDocsSeedBook(bookDao, "b2")

                    bookDao.observeBookIdsWithDocuments().test {
                        awaitItem().shouldBeEmpty()
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }
    })

private suspend fun daoDocsSeedBook(
    bookDao: BookDao,
    id: String,
) {
    bookDao.upsert(
        BookEntity(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Book $id",
            sortTitle = "Book $id",
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
