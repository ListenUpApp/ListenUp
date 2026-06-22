@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.AnalyzedDocument
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Drives the real scan-persist entry point ([BookRepository.upsertFromAnalyzed]) with an
 * [AnalyzedBook] carrying [AnalyzedBook.documents], then asserts the documents land in
 * `book_documents` and read back through [BookPayloadReader]. Exercises the full
 * production chain: `AnalyzedBookMapper.buildDocuments` -> `writePayload` ->
 * `BookAggregateWriter.replaceDocuments` -> SQLDelight -> read-back. Mirrors
 * `BookRepositoryScannerGenreIngestTest`, the sibling child-table ingest test.
 *
 * (The file-walk that fills `AnalyzedBook.documents` from disk is covered by
 * `DocumentCollectorTest`; here the analyzed model is supplied directly.)
 */
class BookRepositoryDocumentIngestTest :
    FunSpec({

        test("documents from an analyzed book persist to book_documents and read back via findById") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val result =
                        repo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(
                                rootRelPath = "books/b1",
                                documents =
                                    listOf(
                                        AnalyzedDocument(relPath = "guide.pdf", format = "pdf", size = 111L, hash = "h1"),
                                        AnalyzedDocument(relPath = "extras/map.pdf", format = "pdf", size = 222L, hash = "h2"),
                                    ),
                            ),
                        )
                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()

                    // Persisted rows are ordinal-keyed by list position and read back ORDER BY ordinal.
                    val rows = sql.bookDocumentsQueries.selectByBookIds(listOf("b1")).executeAsList()
                    rows.map { it.ordinal to it.filename } shouldContainExactly
                        listOf(0L to "guide.pdf", 1L to "extras/map.pdf")
                    rows.first { it.ordinal == 0L }.let { row ->
                        row.format shouldBe "pdf"
                        row.size shouldBe 111L
                        row.hash shouldBe "h1"
                    }

                    // Read-back through BookPayloadReader: filename (the book-root-relative path),
                    // index = ordinal, and a server-assigned non-blank UUID id (analyzed id was blank).
                    val payload = repo.findById(BookId("b1"))!!
                    payload.documents.map { it.filename } shouldContainExactly listOf("guide.pdf", "extras/map.pdf")
                    payload.documents.map { it.index } shouldContainExactly listOf(0, 1)
                    payload.documents.first().let { doc ->
                        doc.format shouldBe "pdf"
                        doc.size shouldBe 111L
                        doc.hash shouldBe "h1"
                    }
                    payload.documents.all { it.id.isNotBlank() } shouldBe true
                }
            }
        }

        test("rescan with a different document set wholesale-replaces book_documents") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(
                            rootRelPath = "books/b1",
                            documents =
                                listOf(
                                    AnalyzedDocument(relPath = "a.pdf", format = "pdf", size = 1L, hash = "ha"),
                                    AnalyzedDocument(relPath = "b.pdf", format = "pdf", size = 2L, hash = "hb"),
                                ),
                        ),
                    )

                    // A re-scan that finds only c.pdf must leave exactly one row (delete-then-insert).
                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(
                            rootRelPath = "books/b1",
                            documents = listOf(AnalyzedDocument(relPath = "c.pdf", format = "pdf", size = 3L, hash = "hc")),
                        ),
                    )

                    sql.bookDocumentsQueries
                        .selectByBookIds(listOf("b1"))
                        .executeAsList()
                        .map { it.filename } shouldContainExactly listOf("c.pdf")
                }
            }
        }
    })

private fun SqlTestDatabases.newRepo(): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}

private fun analyzedFixture(
    rootRelPath: String,
    documents: List<AnalyzedDocument> = emptyList(),
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = rootRelPath,
                isFile = false,
                files = listOf(file),
            ),
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
        documents = documents,
    )
}
