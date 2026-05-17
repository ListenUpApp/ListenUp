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

class BookRepositorySoftDeleteAbsentTest :
    FunSpec({

        test("softDeleteAbsent soft-deletes books not in seenIds, leaves seen books alone") {
            withInMemoryDatabase {
                val db = this
                val (repo, registry) = repository(db, ChangeBus())
                runTest {
                    val libId = registry.currentLibrary()
                    val a = repo.resolveOrInsert(libId, analyzedFor("a", inode = 1L)).resolved()
                    val b = repo.resolveOrInsert(libId, analyzedFor("b", inode = 2L)).resolved()
                    val c = repo.resolveOrInsert(libId, analyzedFor("c", inode = 3L)).resolved()

                    repo.softDeleteAbsent(libId, seenIds = setOf(a, c))

                    repo.findById(a)?.deletedAt shouldBe null
                    repo.findById(b)?.deletedAt shouldNotBe null
                    repo.findById(c)?.deletedAt shouldBe null
                }
            }
        }

        test("softDeleteAbsent emits SyncEvent.Deleted on ChangeBus per swept book") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val (repo, registry) = repository(db, bus)
                runTest {
                    val libId = registry.currentLibrary()
                    val a = repo.resolveOrInsert(libId, analyzedFor("a", inode = 1L)).resolved()
                    repo.resolveOrInsert(libId, analyzedFor("b", inode = 2L)).resolved()

                    val received = mutableListOf<BusEvent<*>>()
                    val collector = launch { bus.subscribe().collect { received += it } }
                    advanceUntilIdle()
                    received.clear() // drop the two replayed Created events

                    repo.softDeleteAbsent(libId, seenIds = setOf(a))
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
                val bus = ChangeBus()
                val (repo, registry) = repository(db, bus)
                runTest {
                    val libId = registry.currentLibrary()
                    val a = repo.resolveOrInsert(libId, analyzedFor("a", inode = 1L)).resolved()
                    val b = repo.resolveOrInsert(libId, analyzedFor("b", inode = 2L)).resolved()

                    repo.softDeleteAbsent(libId, seenIds = setOf(a))
                    val firstRevision = repo.findById(b)?.revision

                    // Second sweep with the same seenIds: b is already tombstoned and must
                    // not be swept again — no revision bump, no second Deleted event.
                    val received = mutableListOf<BusEvent<*>>()
                    val collector = launch { bus.subscribe().collect { received += it } }
                    advanceUntilIdle()
                    received.clear()

                    repo.softDeleteAbsent(libId, seenIds = setOf(a))
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

/**
 * A [BookRepository] paired with the [LibraryRegistry] backing it. Tests pass
 * `registry.currentLibrary()` to `resolveOrInsert` / `softDeleteAbsent` so the
 * resolved id matches the one `writePayload`'s INSERT branch stamps onto rows.
 */
private data class SoftDeleteRepoFixture(
    val repo: BookRepository,
    val registry: LibraryRegistry,
)

private fun repository(
    db: Database,
    bus: ChangeBus,
): SoftDeleteRepoFixture {
    val registry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib"))
    val repo =
        BookRepository(
            db = db,
            bus = bus,
            registry = SyncRegistry(),
            libraryRegistry = registry,
        )
    return SoftDeleteRepoFixture(repo, registry)
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
