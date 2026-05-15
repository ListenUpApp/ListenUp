@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.BusEvent
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class BookRepositorySoftDeleteAbsentTest :
    FunSpec({

        test("softDeleteAbsent soft-deletes books not in seenIds, leaves seen books alone") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = repository(db, ChangeBus())
                runTest {
                    val a = repo.resolveOrInsert(LibraryId("lib1"), analyzedFor("a", inode = 1L)).resolved()
                    val b = repo.resolveOrInsert(LibraryId("lib1"), analyzedFor("b", inode = 2L)).resolved()
                    val c = repo.resolveOrInsert(LibraryId("lib1"), analyzedFor("c", inode = 3L)).resolved()

                    repo.softDeleteAbsent(LibraryId("lib1"), seenIds = setOf(a, c))

                    repo.findById(a)?.deletedAt shouldBe null
                    repo.findById(b)?.deletedAt shouldNotBe null
                    repo.findById(c)?.deletedAt shouldBe null
                }
            }
        }

        test("softDeleteAbsent emits SyncEvent.Deleted on ChangeBus per swept book") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val bus = ChangeBus()
                val repo = repository(db, bus)
                runTest {
                    val a = repo.resolveOrInsert(LibraryId("lib1"), analyzedFor("a", inode = 1L)).resolved()
                    repo.resolveOrInsert(LibraryId("lib1"), analyzedFor("b", inode = 2L)).resolved()

                    val received = mutableListOf<BusEvent<*>>()
                    val collector = launch { bus.subscribe().collect { received += it } }
                    advanceUntilIdle()
                    received.clear() // drop the two replayed Created events

                    repo.softDeleteAbsent(LibraryId("lib1"), seenIds = setOf(a))
                    advanceUntilIdle()
                    collector.cancel()

                    received.size shouldBe 1
                    received.single().event.shouldBeInstanceOf<SyncEvent.Deleted>()
                }
            }
        }

        test("softDeleteAbsent does not re-sweep already-deleted books") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val bus = ChangeBus()
                val repo = repository(db, bus)
                runTest {
                    val a = repo.resolveOrInsert(LibraryId("lib1"), analyzedFor("a", inode = 1L)).resolved()
                    val b = repo.resolveOrInsert(LibraryId("lib1"), analyzedFor("b", inode = 2L)).resolved()

                    repo.softDeleteAbsent(LibraryId("lib1"), seenIds = setOf(a))
                    val firstRevision = repo.findById(b)?.revision

                    // Second sweep with the same seenIds: b is already tombstoned and must
                    // not be swept again — no revision bump, no second Deleted event.
                    val received = mutableListOf<BusEvent<*>>()
                    val collector = launch { bus.subscribe().collect { received += it } }
                    advanceUntilIdle()
                    received.clear()

                    repo.softDeleteAbsent(LibraryId("lib1"), seenIds = setOf(a))
                    advanceUntilIdle()
                    collector.cancel()

                    received.size shouldBe 0
                    repo.findById(b)?.revision shouldBe firstRevision
                }
            }
        }
    })

// --- Result unwrapping ------------------------------------------------------

/**
 * Asserts the [BookRepository.resolveOrInsert] result landed and returns the
 * resolved [BookId]. Fails loudly with the typed error otherwise.
 */
private fun AppResult<BookId>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

// --- Fixtures ---------------------------------------------------------------

private fun repository(
    db: Database,
    bus: ChangeBus,
): BookRepository =
    BookRepository(
        db = db,
        bus = bus,
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
