package com.calypsan.listenup.server.db.sqldelight

import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Query-level tests for [com.calypsan.listenup.server.db.sqldelight.BookDocumentsQueries].
 *
 * Exercises the named queries declared in `BookDocuments.sq` against a real migrated
 * SQLite database — the same path production uses — so FK enforcement (ON DELETE CASCADE)
 * and column types are validated against the live migration DDL.
 */
class BookDocumentsQueriesTest :
    FunSpec({

        test("insert and selectByBookIds returns rows in ordinal order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.transaction {
                    sql.bookDocumentsQueries.insert(
                        book_id = "b1",
                        ordinal = 0L,
                        id = "doc-0",
                        filename = "map.pdf",
                        format = "pdf",
                        size = 1024L,
                        hash = "abc123",
                    )
                    sql.bookDocumentsQueries.insert(
                        book_id = "b1",
                        ordinal = 1L,
                        id = "doc-1",
                        filename = "extras/liner-notes.pdf",
                        format = "pdf",
                        size = 2048L,
                        hash = "def456",
                    )
                }

                val rows = sql.bookDocumentsQueries.selectByBookIds(listOf("b1")).executeAsList()
                rows.map { it.ordinal } shouldBe listOf(0L, 1L)
                rows.map { it.id } shouldBe listOf("doc-0", "doc-1")
                rows.map { it.filename } shouldBe listOf("map.pdf", "extras/liner-notes.pdf")
            }
        }

        test("selectFileForBook returns filename, format, size, and hash for the requested document") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.transaction {
                    sql.bookDocumentsQueries.insert(
                        book_id = "b1",
                        ordinal = 0L,
                        id = "doc-0",
                        filename = "map.pdf",
                        format = "pdf",
                        size = 1024L,
                        hash = "abc123",
                    )
                }

                val row = sql.bookDocumentsQueries.selectFileForBook(book_id = "b1", id = "doc-0").executeAsOneOrNull()
                row?.filename shouldBe "map.pdf"
                row?.format shouldBe "pdf"
                row?.size shouldBe 1024L
                row?.hash shouldBe "abc123"
            }
        }

        test("deleting the parent book cascades and removes its book_documents rows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.transaction {
                    sql.bookDocumentsQueries.insert(
                        book_id = "b1",
                        ordinal = 0L,
                        id = "doc-0",
                        filename = "map.pdf",
                        format = "pdf",
                        size = 1024L,
                        hash = "abc123",
                    )
                    sql.bookDocumentsQueries.insert(
                        book_id = "b1",
                        ordinal = 1L,
                        id = "doc-1",
                        filename = "extras/liner-notes.pdf",
                        format = "pdf",
                        size = 2048L,
                        hash = "def456",
                    )
                }

                // Verify rows exist before deletion.
                val before = sql.bookDocumentsQueries.selectByBookIds(listOf("b1")).executeAsList()
                before.size shouldBe 2

                // Hard-delete the parent book — triggers ON DELETE CASCADE on book_documents.
                driver.execute(null, "DELETE FROM books WHERE id = 'b1'", 0)

                val after = sql.bookDocumentsQueries.selectByBookIds(listOf("b1")).executeAsList()
                after.shouldBeEmpty()
            }
        }

        test("deleteByBookId removes all documents for the given book and leaves other books untouched") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestBook("b2")
                sql.transaction {
                    sql.bookDocumentsQueries.insert(
                        book_id = "b1",
                        ordinal = 0L,
                        id = "doc-0",
                        filename = "map.pdf",
                        format = "pdf",
                        size = 1024L,
                        hash = "abc123",
                    )
                    sql.bookDocumentsQueries.insert(
                        book_id = "b1",
                        ordinal = 1L,
                        id = "doc-1",
                        filename = "extras/liner-notes.pdf",
                        format = "pdf",
                        size = 2048L,
                        hash = "def456",
                    )
                    sql.bookDocumentsQueries.insert(
                        book_id = "b2",
                        ordinal = 0L,
                        id = "doc-2",
                        filename = "index.pdf",
                        format = "pdf",
                        size = 512L,
                        hash = "ghi789",
                    )
                }

                // Directly exercise the wholesale-replace delete query.
                sql.bookDocumentsQueries.deleteByBookId("b1")

                val afterB1 = sql.bookDocumentsQueries.selectByBookIds(listOf("b1")).executeAsList()
                afterB1.shouldBeEmpty()

                // b2 rows must be unaffected.
                val afterB2 = sql.bookDocumentsQueries.selectByBookIds(listOf("b2")).executeAsList()
                afterB2.size shouldBe 1
            }
        }
    })
