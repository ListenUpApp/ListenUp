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
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

class BookRepositoryResolveTest :
    FunSpec({

        test("same path → existing UUID") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = repository(db)
                runTest {
                    val analyzed = analyzedFor(rootRelPath = "Sanderson/Way of Kings", inode = 1001L)
                    val firstId = repo.resolveOrInsert(LibraryId("lib1"), analyzed).resolved()
                    val secondId = repo.resolveOrInsert(LibraryId("lib1"), analyzed).resolved()
                    secondId shouldBe firstId
                }
            }
        }

        test("path miss, same inode → existing UUID + path update") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = repository(db)
                runTest {
                    val original = analyzedFor(rootRelPath = "Sanderson/Way of Kings", inode = 1001L)
                    val originalId = repo.resolveOrInsert(LibraryId("lib1"), original).resolved()

                    val moved =
                        original.copy(
                            candidate = original.candidate.copy(rootRelPath = "Sanderson/WayOfKings"),
                        )
                    val movedId = repo.resolveOrInsert(LibraryId("lib1"), moved).resolved()

                    movedId shouldBe originalId
                    repo.findById(originalId)?.rootRelPath shouldBe "Sanderson/WayOfKings"
                }
            }
        }

        test("path miss, no inode match → new UUID") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = repository(db)
                runTest {
                    val a = analyzedFor(rootRelPath = "a", inode = 1L)
                    val b = analyzedFor(rootRelPath = "b", inode = 2L)
                    val idA = repo.resolveOrInsert(LibraryId("lib1"), a).resolved()
                    val idB = repo.resolveOrInsert(LibraryId("lib1"), b).resolved()
                    idA shouldNotBe idB
                }
            }
        }

        test("null inode falls through to new UUID") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = repository(db)
                runTest {
                    val a = analyzedFor(rootRelPath = "a", inode = null)
                    val b = analyzedFor(rootRelPath = "b", inode = null)
                    val idA = repo.resolveOrInsert(LibraryId("lib1"), a).resolved()
                    val idB = repo.resolveOrInsert(LibraryId("lib1"), b).resolved()
                    idA shouldNotBe idB
                }
            }
        }

        test("resolveOrInsert returns AppResult.Success when the write lands") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = repository(db)
                runTest {
                    val analyzed = analyzedFor(rootRelPath = "Sanderson/Mistborn", inode = 5005L)
                    val result = repo.resolveOrInsert(LibraryId("lib1"), analyzed)
                    result.shouldBeInstanceOf<AppResult.Success<BookId>>()
                }
            }
        }

        test("inode match logs the move at INFO") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = repository(db)
                runTest {
                    val appender = attachRootAppender()
                    try {
                        val original = analyzedFor(rootRelPath = "old/path", inode = 7777L)
                        repo.resolveOrInsert(LibraryId("lib1"), original)

                        val moved =
                            original.copy(
                                candidate = original.candidate.copy(rootRelPath = "new/path"),
                            )
                        repo.resolveOrInsert(LibraryId("lib1"), moved)

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

private fun repository(db: Database): BookRepository =
    BookRepository(
        db = db,
        bus = ChangeBus(),
        registry = SyncRegistry(),
        libraryId = LibraryId("lib1"),
    )

private fun seedLibrary(db: Database) {
    transaction(db) {
        LibraryTable.insert {
            it[id] = "lib1"
            it[name] = "Default"
            it[rootPath] = "/lib"
        }
    }
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
