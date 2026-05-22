package com.calypsan.listenup.server.audio

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest
import java.nio.file.Path

class AudioFileLocatorTest :
    FunSpec({

        test("locate returns AudioFileLocation with correct path, format, and sizeBytes") {
            withInMemoryDatabase {
                val db = this
                val libraryPath = "/fake/library"
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = registry,
                        libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to libraryPath)),
                        contributorRepository = ContributorRepository(db, bus, registry),
                        seriesRepository = SeriesRepository(db, bus, registry),
                    )

                runTest {
                    repo.upsert(locatorFixture(bookId = "b1", fileId = "af1", rootRelPath = "Sanderson/WayOfKings", filename = "01.m4b", format = "m4b", size = 500_000_000L))

                    val locator = AudioFileLocator(db)
                    val result = locator.locate("b1", "af1")

                    result.shouldNotBeNull()
                    result.path shouldBe Path.of(libraryPath, "Sanderson/WayOfKings", "01.m4b")
                    result.format shouldBe "m4b"
                    result.sizeBytes shouldBe 500_000_000L
                }
            }
        }

        test("locate returns null for an unknown bookId") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val locator = AudioFileLocator(db)
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
