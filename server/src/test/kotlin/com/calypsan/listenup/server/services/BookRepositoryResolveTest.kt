@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

class BookRepositoryResolveTest :
    FunSpec({

        test("same path → existing UUID") {
            withInMemoryDatabase {
                val db = this
                val (repo, registry) = repository(db)
                runTest {
                    val libId = registry.currentLibrary()
                    val analyzed = analyzedFor(rootRelPath = "Sanderson/Way of Kings", inode = 1001L)
                    val firstId = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzed).resolved()
                    val secondId = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzed).resolved()
                    secondId shouldBe firstId
                }
            }
        }

        test("path miss, same inode → existing UUID + path update") {
            withInMemoryDatabase {
                val db = this
                val (repo, registry) = repository(db)
                runTest {
                    val libId = registry.currentLibrary()
                    val original = analyzedFor(rootRelPath = "Sanderson/Way of Kings", inode = 1001L)
                    val originalId = repo.resolveOrInsert(libId, TEST_FOLDER_ID, original).resolved()

                    val moved =
                        original.copy(
                            candidate = original.candidate.copy(rootRelPath = "Sanderson/WayOfKings"),
                        )
                    val movedId = repo.resolveOrInsert(libId, TEST_FOLDER_ID, moved).resolved()

                    movedId shouldBe originalId
                    repo.findById(originalId)?.rootRelPath shouldBe "Sanderson/WayOfKings"
                }
            }
        }

        test("path miss, no inode match → new UUID") {
            withInMemoryDatabase {
                val db = this
                val (repo, registry) = repository(db)
                runTest {
                    val libId = registry.currentLibrary()
                    val a = analyzedFor(rootRelPath = "a", inode = 1L)
                    val b = analyzedFor(rootRelPath = "b", inode = 2L)
                    val idA = repo.resolveOrInsert(libId, TEST_FOLDER_ID, a).resolved()
                    val idB = repo.resolveOrInsert(libId, TEST_FOLDER_ID, b).resolved()
                    idA shouldNotBe idB
                }
            }
        }

        test("null inode falls through to new UUID") {
            withInMemoryDatabase {
                val db = this
                val (repo, registry) = repository(db)
                runTest {
                    val libId = registry.currentLibrary()
                    val a = analyzedFor(rootRelPath = "a", inode = null)
                    val b = analyzedFor(rootRelPath = "b", inode = null)
                    val idA = repo.resolveOrInsert(libId, TEST_FOLDER_ID, a).resolved()
                    val idB = repo.resolveOrInsert(libId, TEST_FOLDER_ID, b).resolved()
                    idA shouldNotBe idB
                }
            }
        }

        test("resolveOrInsert returns AppResult.Success when the write lands") {
            withInMemoryDatabase {
                val db = this
                val (repo, registry) = repository(db)
                runTest {
                    val libId = registry.currentLibrary()
                    val analyzed = analyzedFor(rootRelPath = "Sanderson/Mistborn", inode = 5005L)
                    val result = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzed)
                    result.shouldBeInstanceOf<AppResult.Success<BookId>>()
                }
            }
        }

        test("inode match logs the move at INFO") {
            withInMemoryDatabase {
                val db = this
                val (repo, registry) = repository(db)
                runTest {
                    val libId = registry.currentLibrary()
                    val appender = attachRootAppender()
                    try {
                        val original = analyzedFor(rootRelPath = "old/path", inode = 7777L)
                        repo.resolveOrInsert(libId, TEST_FOLDER_ID, original)

                        val moved =
                            original.copy(
                                candidate = original.candidate.copy(rootRelPath = "new/path"),
                            )
                        repo.resolveOrInsert(libId, TEST_FOLDER_ID, moved)

                        val moveEvent =
                            appender.list
                                .firstOrNull { it.formattedMessage.startsWith("Book moved:") }
                                .shouldNotBeNull()
                        moveEvent.level shouldBe Level.INFO
                        moveEvent.formattedMessage shouldBe "Book moved: old/path → new/path"
                    } finally {
                        detachRootAppender(appender)
                    }
                }
            }
        }
    })

// --- Log capture ------------------------------------------------------------

/**
 * Attaches a logback [ListAppender] to the root logger so the move-detection
 * INFO line can be asserted. Root-level so it is agnostic to kotlin-logging's
 * file-facade logger name.
 */
private fun attachRootAppender(): ListAppender<ILoggingEvent> {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    return appender
}

private fun detachRootAppender(appender: ListAppender<ILoggingEvent>) {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    root.detachAppender(appender)
    appender.stop()
}

// --- Constants --------------------------------------------------------------

private val TEST_FOLDER_ID = FolderId("test-folder")

// --- Result unwrapping ------------------------------------------------------

/**
 * Asserts the [resolveOrInsert] result landed and returns the resolved [BookId].
 * Fails the test loudly with the typed error if the aggregate write did not land.
 */
private fun AppResult<BookId>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

// --- Fixtures ---------------------------------------------------------------

/**
 * A [BookRepository] paired with the [LibraryRegistry] backing it. Tests pass
 * `registry.currentLibrary()` to `resolveOrInsert` so the resolved id matches
 * the one `writePayload`'s INSERT branch stamps onto new book rows.
 */
private data class ResolveRepoFixture(
    val repo: BookRepository,
    val registry: LibraryRegistry,
)

private fun repository(db: Database): ResolveRepoFixture {
    val registry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib"))
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val repo =
        BookRepository(
            db = db,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = ContributorRepository(db, bus, syncRegistry),
            seriesRepository = SeriesRepository(db, bus, syncRegistry),
        )
    return ResolveRepoFixture(repo, registry)
}

/**
 * Builds a minimal [AnalyzedBook] anchored at [rootRelPath] with one audio file
 * whose [FileEntry.inode] is [inode]. The book-level inode used by
 * `resolveOrInsert` is that first file's inode.
 */
private fun analyzedFor(
    rootRelPath: String,
    inode: Long?,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = inode,
            fileType = FileType.AUDIO,
        )
    val candidate =
        CandidateBook(
            rootRelPath = rootRelPath,
            isFile = false,
            files = listOf(file),
        )
    return AnalyzedBook(
        candidate = candidate,
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
    )
}
