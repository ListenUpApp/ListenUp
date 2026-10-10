package com.calypsan.listenup.server.document

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookDocumentPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class DocumentFileLocatorTest :
    FunSpec({

        test("locate resolves the absolute path (incl. a subfolder filename), format, size, and hash") {
            withSqlDatabase {
                val folderPath = "/fake/library"
                val bus = ChangeBus()
                val registry = SyncRegistry()

                sql.seedTestLibraryAndFolder(folderPath = folderPath)

                val repo =
                    BookRepository(
                        db = sql,
                        driver = driver,
                        bus = bus,
                        registry = registry,
                        contributorRepository = ContributorRepository(sql, bus, registry),
                        seriesRepository = SeriesRepository(sql, bus, registry),
                        genreRepository = GenreRepository(sql, bus, registry),
                    )

                runTest {
                    repo.upsert(
                        locatorFixture(
                            bookId = "b1",
                            docId = "doc1",
                            rootRelPath = "Sanderson/WayOfKings",
                            // A book-root-relative path that carries a subfolder — Path composition must preserve it.
                            filename = "extras/map.pdf",
                            format = "pdf",
                            size = 12_345L,
                            hash = "deadbeef",
                        ),
                    )

                    val result = DocumentFileLocator(sql).locate("b1", "doc1")

                    result.shouldNotBeNull()
                    result.path shouldBe Path(folderPath, "Sanderson/WayOfKings", "extras/map.pdf")
                    result.format shouldBe "pdf"
                    result.sizeBytes shouldBe 12_345L
                    result.hash shouldBe "deadbeef"
                }
            }
        }

        fun seedAndLocate(
            makeFile: (libraryRoot: java.nio.file.Path, bookDir: java.nio.file.Path, outside: java.nio.file.Path) -> Unit,
        ): Any? {
            val libraryRoot = java.nio.file.Files.createTempDirectory("locator-lib")
            val outside = java.nio.file.Files.createTempDirectory("locator-outside")
            try {
                val bookDir = java.nio.file.Files.createDirectories(libraryRoot.resolve("Author/Book"))
                makeFile(libraryRoot, bookDir, outside)
                var located: Any? = null
                withSqlDatabase {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toRealPath().toString())
                    val repo =
                        BookRepository(
                            db = sql,
                            driver = driver,
                            bus = bus,
                            registry = registry,
                            contributorRepository = ContributorRepository(sql, bus, registry),
                            seriesRepository = SeriesRepository(sql, bus, registry),
                            genreRepository = GenreRepository(sql, bus, registry),
                        )
                    runTest {
                    repo.upsert(
                        locatorFixture(
                            bookId = "b1",
                            docId = "doc1",
                            rootRelPath = "Author/Book",
                            filename = "map.pdf",
                            format = "pdf",
                            size = 1L, hash = "h",
                        ),
                    )
                    located = DocumentFileLocator(sql).locate("b1", "doc1")
                    }
                }
                return located
            } finally {
                libraryRoot.toFile().deleteRecursively()
                outside.toFile().deleteRecursively()
            }
        }

        test("locate returns null when the file is a link to a file outside the library") {
            val result =
                seedAndLocate { _, bookDir, outside ->
                    val target = java.nio.file.Files.writeString(outside.resolve("secret"), "x")
                    java.nio.file.Files.createSymbolicLink(bookDir.resolve("map.pdf"), target)
                }
            result.shouldBeNull()
        }

        test("locate returns the location for a plain file inside the library") {
            val result =
                seedAndLocate { _, bookDir, _ ->
                    java.nio.file.Files.writeString(bookDir.resolve("map.pdf"), "x")
                }
            result.shouldNotBeNull()
        }

        test("locate still returns the location for a file that is not on disk") {
            val result = seedAndLocate { _, _, _ -> }
            result.shouldNotBeNull()
        }

        test("locate returns null for an unknown bookId") {
            withSqlDatabase {
                runTest {
                    DocumentFileLocator(sql).locate("unknown", "anything").shouldBeNull()
                }
            }
        }
    })

private fun locatorFixture(
    bookId: String,
    docId: String,
    rootRelPath: String,
    filename: String,
    format: String,
    size: Long,
    hash: String,
): BookSyncPayload =
    BookSyncPayload(
        id = bookId,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Test Book",
        sortTitle = "Test Book",
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$bookId",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters = emptyList(),
        documents =
            listOf(
                BookDocumentPayload(
                    id = docId,
                    index = 0,
                    filename = filename,
                    format = format,
                    size = size,
                    hash = hash,
                ),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
