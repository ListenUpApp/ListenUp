@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.BusEvent
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

class BookRepositorySoftDeleteAbsentTest :
    FunSpec({

        test("softDeleteAbsent soft-deletes books not in seenIds, leaves seen books alone") {
            withSqlDatabase {
                val (repo, registry) = repository(sql, driver, ChangeBus())
                runTest {
                    val libId = registry.currentLibrary()
                    val a = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("a", inode = 1L)).resolved()
                    val b = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("b", inode = 2L)).resolved()
                    val c = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("c", inode = 3L)).resolved()

                    repo.softDeleteAbsent(libId, seenIds = setOf(a, c))

                    repo.findById(a)?.deletedAt shouldBe null
                    repo.findById(b)?.deletedAt shouldNotBe null
                    repo.findById(c)?.deletedAt shouldBe null
                }
            }
        }

        test("softDeleteAbsent emits SyncEvent.Deleted on ChangeBus per swept book") {
            withSqlDatabase {
                val bus = ChangeBus()
                val (repo, registry) = repository(sql, driver, bus)
                runTest {
                    val libId = registry.currentLibrary()
                    val a = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("a", inode = 1L)).resolved()
                    repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("b", inode = 2L)).resolved()

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
            withSqlDatabase {
                val bus = ChangeBus()
                val (repo, registry) = repository(sql, driver, bus)
                runTest {
                    val libId = registry.currentLibrary()
                    val a = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("a", inode = 1L)).resolved()
                    val b = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("b", inode = 2L)).resolved()

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

        test("softDeleteAbsentByPaths soft-deletes books not in seenPaths, leaves seen books alone") {
            withSqlDatabase {
                val (repo, registry) = repository(sql, driver, ChangeBus())
                runTest {
                    val libId = registry.currentLibrary()
                    val a = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("a", inode = 1L)).resolved()
                    val b = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("b", inode = 2L)).resolved()
                    val c = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("c", inode = 3L)).resolved()

                    // Seen paths: "a" and "c" — "b" is absent from disk.
                    repo.softDeleteAbsentByPaths(
                        libId,
                        seen = setOf(FolderScopedPath(TEST_FOLDER_ID, "a"), FolderScopedPath(TEST_FOLDER_ID, "c")),
                    )

                    repo.findById(a)?.deletedAt shouldBe null
                    repo.findById(b)?.deletedAt shouldNotBe null
                    repo.findById(c)?.deletedAt shouldBe null
                }
            }
        }

        test("softDeleteAbsentByPaths emits SyncEvent.Deleted on ChangeBus per swept book") {
            withSqlDatabase {
                val bus = ChangeBus()
                val (repo, registry) = repository(sql, driver, bus)
                runTest {
                    val libId = registry.currentLibrary()
                    repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("a", inode = 1L))
                    repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("b", inode = 2L))

                    val received = mutableListOf<BusEvent<*>>()
                    val collector = launch { bus.subscribe().collect { received += it } }
                    advanceUntilIdle()
                    received.clear()

                    // Only "a" is seen; "b" should be swept.
                    repo.softDeleteAbsentByPaths(libId, seen = setOf(FolderScopedPath(TEST_FOLDER_ID, "a")))
                    advanceUntilIdle()
                    collector.cancel()

                    received.size shouldBe 1
                    received.single().event.shouldBeInstanceOf<SyncEvent.Deleted>()
                }
            }
        }

        test("softDeleteAbsentByPaths does not re-sweep already-deleted books") {
            withSqlDatabase {
                val bus = ChangeBus()
                val (repo, registry) = repository(sql, driver, bus)
                runTest {
                    val libId = registry.currentLibrary()
                    repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("a", inode = 1L))
                    val b = repo.resolveOrInsert(libId, TEST_FOLDER_ID, analyzedFor("b", inode = 2L)).resolved()

                    repo.softDeleteAbsentByPaths(libId, seen = setOf(FolderScopedPath(TEST_FOLDER_ID, "a")))
                    val firstRevision = repo.findById(b)?.revision

                    val received = mutableListOf<BusEvent<*>>()
                    val collector = launch { bus.subscribe().collect { received += it } }
                    advanceUntilIdle()
                    received.clear()

                    // Second sweep: b is already tombstoned — must not be swept again.
                    repo.softDeleteAbsentByPaths(libId, seen = setOf(FolderScopedPath(TEST_FOLDER_ID, "a")))
                    advanceUntilIdle()
                    collector.cancel()

                    received.size shouldBe 0
                    repo.findById(b)?.revision shouldBe firstRevision
                }
            }
        }

        test("softDeleteAbsentByPaths spares a book whose file left the walked path for the path its row names") {
            sweepOverDisk { repo, root ->
                val moved = repo.resolveOrInsert(SEEDED_LIBRARY_ID, TEST_FOLDER_ID, analyzedFor("new", inode = 7L)).resolved()
                root.writeAudio("new/01.m4b")

                repo.softDeleteAbsentByPaths(
                    SEEDED_LIBRARY_ID,
                    seen = setOf(FolderScopedPath(TEST_FOLDER_ID, "old")),
                    walkedIdentityFiles = mapOf(FolderScopedInode(TEST_FOLDER_ID, 7L) to root.resolve("old/01.m4b").toString()),
                ) shouldBe 0

                repo.findById(moved)?.deletedAt shouldBe null
            }
        }

        test("softDeleteAbsentByPaths still sweeps a book whose walked file is still where the walk saw it") {
            // The walk saw this inode at "regrouped" and the file is still there: the book was folded
            // into another one (or otherwise re-read), not moved after the walk, so its old row goes.
            sweepOverDisk { repo, root ->
                val stale = repo.resolveOrInsert(SEEDED_LIBRARY_ID, TEST_FOLDER_ID, analyzedFor("stale", inode = 7L)).resolved()
                root.writeAudio("stale/01.m4b")
                root.writeAudio("regrouped/01.m4b")

                repo.softDeleteAbsentByPaths(
                    SEEDED_LIBRARY_ID,
                    seen = setOf(FolderScopedPath(TEST_FOLDER_ID, "regrouped")),
                    walkedIdentityFiles =
                        mapOf(FolderScopedInode(TEST_FOLDER_ID, 7L) to root.resolve("regrouped/01.m4b").toString()),
                ) shouldBe 1

                repo.findById(stale)?.deletedAt shouldNotBe null
            }
        }

        test("softDeleteAbsentByPaths still sweeps a moved-looking book whose audio is not on disk either") {
            sweepOverDisk { repo, root ->
                val gone = repo.resolveOrInsert(SEEDED_LIBRARY_ID, TEST_FOLDER_ID, analyzedFor("new", inode = 7L)).resolved()

                repo.softDeleteAbsentByPaths(
                    SEEDED_LIBRARY_ID,
                    seen = emptySet(),
                    walkedIdentityFiles = mapOf(FolderScopedInode(TEST_FOLDER_ID, 7L) to root.resolve("old/01.m4b").toString()),
                ) shouldBe 1

                repo.findById(gone)?.deletedAt shouldNotBe null
            }
        }
    })

private val SEEDED_LIBRARY_ID = LibraryId("test-library")

/** A sweep over a real library folder on disk, registered as [TEST_FOLDER_ID]. */
private fun sweepOverDisk(block: suspend (BookRepository, Path) -> Unit) {
    val root = Files.createTempDirectory("listenup-sweep-")
    try {
        withSqlDatabase {
            sql.seedTestLibraryAndFolder(folderId = TEST_FOLDER_ID.value, folderPath = root.toString())
            val (repo, _) = repository(sql, driver, ChangeBus())
            runTest { block(repo, root) }
        }
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun Path.writeAudio(relPath: String) {
    val file = resolve(relPath)
    Files.createDirectories(file.parent)
    Files.writeString(file, "audio")
}

// --- Constants --------------------------------------------------------------

private val TEST_FOLDER_ID = FolderId("test-folder")

// --- Result unwrapping ------------------------------------------------------

/**
 * Asserts the [BookRepository.resolveOrInsert] result landed and returns the
 * resolved [BookId]. Fails loudly with the typed error otherwise.
 */
private fun AppResult<IngestOutcome>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data.bookId
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
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
    bus: ChangeBus,
): SoftDeleteRepoFixture {
    val registry = LibraryRegistry(sql)
    val syncRegistry = SyncRegistry()
    val repo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = ContributorRepository(sql, bus, syncRegistry),
            seriesRepository = SeriesRepository(sql, bus, syncRegistry),
            genreRepository = GenreRepository(sql, bus, syncRegistry),
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
