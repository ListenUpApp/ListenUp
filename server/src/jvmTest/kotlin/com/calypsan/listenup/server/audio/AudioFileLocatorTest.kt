package com.calypsan.listenup.server.audio

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
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

class AudioFileLocatorTest :
    FunSpec({

        test("locate returns AudioFileLocation with correct path, format, and sizeBytes") {
            withSqlDatabase {
                val folderPath = "/fake/library"
                val bus = ChangeBus()
                val registry = SyncRegistry()

                // Seed a library + folder row so AudioFileLocator can resolve the path.
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
                            fileId = "af1",
                            rootRelPath = "Sanderson/WayOfKings",
                            filename = "01.m4b",
                            format = "m4b",
                            size = 500_000_000L,
                        ),
                    )

                    val locator = AudioFileLocator(sql)
                    val result = locator.locate("b1", "af1")

                    result.shouldNotBeNull()
                    result.path shouldBe Path(folderPath, "Sanderson/WayOfKings", "01.m4b")
                    result.format shouldBe "m4b"
                    result.sizeBytes shouldBe 500_000_000L
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
                            fileId = "af1",
                            rootRelPath = "Author/Book",
                            filename = "01.m4b",
                            format = "m4b",
                            size = 1L,
                        ),
                    )
                    located = AudioFileLocator(sql).locate("b1", "af1")
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
                    java.nio.file.Files.createSymbolicLink(bookDir.resolve("01.m4b"), target)
                }
            result.shouldBeNull()
        }

        test("locate returns the location for a plain file inside the library") {
            val result =
                seedAndLocate { _, bookDir, _ ->
                    java.nio.file.Files.writeString(bookDir.resolve("01.m4b"), "x")
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
                    val locator = AudioFileLocator(sql)
                    val result = locator.locate("unknown", "anything")
                    result.shouldBeNull()
                }
            }
        }
    })

private fun locatorFixture(
    bookId: String,
    fileId: String,
    rootRelPath: String,
    filename: String,
    format: String,
    size: Long,
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
                    id = fileId,
                    index = 0,
                    filename = filename,
                    format = format,
                    codec = "aac",
                    duration = 3_600_000L,
                    size = size,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$bookId", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
