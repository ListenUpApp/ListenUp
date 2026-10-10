package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory

private val LIBRARY = LibraryId("test-library")
private val FOLDER = FolderId("test-folder")

/**
 * Pins how the scanner decides that a book folder it meets is a book it already knows.
 *
 * Resolution runs path first, then the inode of the first audio file. An inode number alone is not
 * identity — it is reused after a delete and shared by hardlinks — so a genuine move must still
 * keep the book's id while a stranger that merely holds the same number must not inherit it.
 * Both entry points are driven because they resolve identity in separate code.
 */
class BookIdentityResolutionTest :
    FunSpec({

        context("bulk ingest") {
            test("a moved book folder keeps its id") {
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder()
                    runTest {
                        val fx = resolutionFixture(this@withSqlDatabase)
                        fx.ingestAll(book("Author/Old Title", inode = 100L, size = 5_000L))
                        val original = fx.idAt("Author/Old Title").shouldNotBeNull()

                        fx.ingestAll(book("Author/New Title", inode = 100L, size = 5_000L))

                        fx.idAt("Author/New Title") shouldBe original
                        fx.rowCount() shouldBe 1
                    }
                }
            }

            test("a stranger that reuses a deleted book's inode gets its own id") {
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder()
                    runTest {
                        val fx = resolutionFixture(this@withSqlDatabase)
                        fx.ingestAll(book("A", inode = 100L, size = 5_000L))
                        val deleted = fx.idAt("A").shouldNotBeNull()
                        fx.softDelete(deleted)

                        fx.ingestAll(book("B", inode = 100L, size = 9_999L))

                        fx.idAt("B").shouldNotBeNull() shouldNotBe deleted
                        fx.isTombstoned(deleted) shouldBe true
                    }
                }
            }

            test("a hardlinked twin is its own book") {
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder()
                    runTest {
                        val fx = resolutionFixture(this@withSqlDatabase)
                        fx.ingestAll(book("X", inode = 100L, size = 5_000L))
                        val xId = fx.idAt("X").shouldNotBeNull()

                        val both = arrayOf(book("X", inode = 100L, size = 5_000L), book("Y", inode = 100L, size = 5_000L))
                        fx.ingestAll(*both)
                        val yId = fx.idAt("Y").shouldNotBeNull()

                        fx.idAt("X") shouldBe xId
                        yId shouldNotBe xId

                        fx.ingestAll(*both)
                        fx.idAt("X") shouldBe xId
                        fx.idAt("Y") shouldBe yId
                    }
                }
            }

            test("a deleted book that comes back at a new path is revived") {
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder()
                    runTest {
                        val fx = resolutionFixture(this@withSqlDatabase)
                        fx.ingestAll(book("A", inode = 100L, size = 5_000L))
                        val original = fx.idAt("A").shouldNotBeNull()
                        fx.softDelete(original)

                        fx.ingestAll(book("Moved/A", inode = 100L, size = 5_000L))

                        fx.idAt("Moved/A") shouldBe original
                        fx.isTombstoned(original) shouldBe false
                    }
                }
            }

            test("a book with no inode is matched by path only") {
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder()
                    runTest {
                        val fx = resolutionFixture(this@withSqlDatabase)
                        fx.ingestAll(book("A", inode = null, size = 5_000L))
                        val first = fx.idAt("A").shouldNotBeNull()

                        fx.ingestAll(book("B", inode = null, size = 5_000L))

                        fx.idAt("B").shouldNotBeNull() shouldNotBe first
                    }
                }
            }
        }

        context("bulk ingest of only the changed books") {
            test("a hardlinked twin is its own book while the original's folder is still on disk") {
                val libraryRoot = createTempDirectory("book-identity")
                try {
                    libraryRoot.resolve("X").createDirectories()
                    withSqlDatabase {
                        sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                        runTest {
                            val fx = resolutionFixture(this@withSqlDatabase)
                            fx.ingestAll(book("X", inode = 100L, size = 5_000L))
                            val xId = fx.idAt("X").shouldNotBeNull()

                            fx.ingestAll(book("Y", inode = 100L, size = 5_000L))

                            fx.idAt("Y").shouldNotBeNull() shouldNotBe xId
                            fx.idAt("X") shouldBe xId
                        }
                    }
                } finally {
                    libraryRoot.toFile().deleteRecursively()
                }
            }

            test("a folder that moved away from disk keeps its id when only the new location is ingested") {
                val libraryRoot = createTempDirectory("book-identity")
                try {
                    withSqlDatabase {
                        sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                        runTest {
                            val fx = resolutionFixture(this@withSqlDatabase)
                            fx.ingestAll(book("X", inode = 100L, size = 5_000L))
                            val xId = fx.idAt("X").shouldNotBeNull()

                            fx.ingestAll(book("Y", inode = 100L, size = 5_000L))

                            fx.idAt("Y") shouldBe xId
                        }
                    }
                } finally {
                    libraryRoot.toFile().deleteRecursively()
                }
            }
        }

        context("single ingest") {
            test("a moved book folder keeps its id") {
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder()
                    runTest {
                        val fx = resolutionFixture(this@withSqlDatabase)
                        val original = fx.ingestOne(book("Author/Old Title", inode = 100L, size = 5_000L))

                        val moved = fx.ingestOne(book("Author/New Title", inode = 100L, size = 5_000L))

                        moved.bookId shouldBe original.bookId
                        moved.wasNew shouldBe false
                    }
                }
            }

            test("a stranger that reuses a deleted book's inode gets its own id") {
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder()
                    runTest {
                        val fx = resolutionFixture(this@withSqlDatabase)
                        val deleted = fx.ingestOne(book("A", inode = 100L, size = 5_000L)).bookId
                        fx.softDelete(deleted)

                        val stranger = fx.ingestOne(book("B", inode = 100L, size = 9_999L))

                        stranger.bookId shouldNotBe deleted
                        stranger.wasNew shouldBe true
                        fx.isTombstoned(deleted) shouldBe true
                    }
                }
            }

            test("a hardlinked twin is its own book") {
                // A lone ingest cannot see its sibling, so it asks the disk whether X's folder is still there.
                val libraryRoot = createTempDirectory("book-identity")
                try {
                    libraryRoot.resolve("X").createDirectories()
                    withSqlDatabase {
                        sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                        runTest {
                            val fx = resolutionFixture(this@withSqlDatabase)
                            val x = fx.ingestOne(book("X", inode = 100L, size = 5_000L))

                            val y = fx.ingestOne(book("Y", inode = 100L, size = 5_000L))

                            y.bookId shouldNotBe x.bookId
                            y.wasNew shouldBe true
                            fx.idAt("X") shouldBe x.bookId
                            fx.ingestOne(book("X", inode = 100L, size = 5_000L)).bookId shouldBe x.bookId
                            fx.ingestOne(book("Y", inode = 100L, size = 5_000L)).bookId shouldBe y.bookId
                        }
                    }
                } finally {
                    libraryRoot.toFile().deleteRecursively()
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private class ResolutionFixture(
    private val dbs: SqlTestDatabases,
    private val repo: BookRepository,
) {
    suspend fun ingestAll(vararg books: AnalyzedBook) {
        val batch = books.toList()
        repo.resolveOrInsertAll(
            libraryId = LIBRARY,
            folderId = FOLDER,
            books = batch,
            coversByBook = emptyMap(),
            systemCollectionId = null,
            identityMaps = repo.resolveScanIdentities(batch),
            onProgress = { _, _ -> },
        )
    }

    suspend fun ingestOne(book: AnalyzedBook): IngestOutcome {
        val result = repo.resolveOrInsert(LIBRARY, FOLDER, book)
        require(result is AppResult.Success) { "resolveOrInsert failed: $result" }
        return result.data
    }

    suspend fun softDelete(id: BookId) {
        require(repo.softDelete(id, clientOpId = null) is AppResult.Success)
    }

    fun idAt(rootRelPath: String): BookId? =
        dbs.sql.booksQueries
            .selectIdByNaturalKey(FOLDER.value, rootRelPath)
            .executeAsOneOrNull()
            ?.let { BookId(it) }

    fun isTombstoned(id: BookId): Boolean =
        dbs.sql.booksQueries
            .selectById(id.value)
            .executeAsOne()
            .deleted_at != null

    fun rowCount(): Int =
        dbs.sql.booksQueries
            .selectIdsByFolder(FOLDER.value)
            .executeAsList()
            .size
}

private fun resolutionFixture(dbs: SqlTestDatabases): ResolutionFixture {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val repo =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(dbs.sql, bus, registry),
            seriesRepository = SeriesRepository(dbs.sql, bus, registry),
            genreRepository = GenreRepository(dbs.sql, bus, registry),
        )
    return ResolutionFixture(dbs, repo)
}

/** A one-file book whose first audio file has exactly the given [inode] and [size]. */
private fun book(
    rootRelPath: String,
    inode: Long?,
    size: Long,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = size,
            mtimeMs = 0L,
            inode = inode,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file)),
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
    )
}
