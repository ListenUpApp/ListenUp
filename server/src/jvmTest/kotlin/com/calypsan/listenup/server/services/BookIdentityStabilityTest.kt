@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for the library-wide book-identity corruption: a rescan (or a folder
 * remove+re-add over the same files) must NOT re-mint every book's UUID. Identity is anchored to
 * `(folder_id, root_rel_path)` — a STABLE per-folder id — so:
 *
 *  - a plain rescan resolves the same book,
 *  - a folder removed then re-added at the same path reuses the folder id and REVIVES its books
 *    under their original UUIDs (instead of re-adding fresh UUIDs and stranding every client),
 *  - a genuine intra-folder move (inode hint) preserves the id,
 *  - two folders sharing a relative path never collide, in resolution or in the tombstone sweep.
 */
class BookIdentityStabilityTest :
    FunSpec({

        test("re-scanning the same files keeps the same book id and creates no tombstones") {
            withSqlDatabase {
                val (repo, _, registry) = fixture(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val folder = FolderId("folder-a")

                    val first = repo.resolveOrInsert(libId, folder, analyzedFor("Book A", inode = 1L)).resolved()
                    val second = repo.resolveOrInsert(libId, folder, analyzedFor("Book A", inode = 1L)).resolved()

                    second shouldBe first
                    repo.findById(first)?.deletedAt shouldBe null
                }
            }
        }

        test("removing a folder then re-adding it at the same path revives the same book ids under the reused folder id") {
            withSqlDatabase {
                val (repo, folderRepo, registry) = fixture(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val folderId = FolderId("folder-a")
                    val rootPath = "/srv/audio/A"

                    // Register the folder and ingest a book under it.
                    folderRepo.upsert(folderPayload(folderId, libId, rootPath))
                    val originalId = repo.resolveOrInsert(libId, folderId, analyzedFor("Book A", inode = 1L)).resolved()

                    // removeFolder: soft-delete the folder's books, then the folder row itself.
                    repo.softDelete(originalId)
                    folderRepo.softDelete(folderId)
                    repo.findById(originalId)?.deletedAt shouldNotBe null

                    // addFolder over the SAME path: the soft-deleted folder is found and its id reused.
                    val reused = folderRepo.findDeletedByRootPath(rootPath)
                    reused shouldNotBe null
                    reused!!.id shouldBe folderId.value

                    // Re-register under the reused id (revives the folder row) and revive its books.
                    folderRepo.upsert(folderPayload(FolderId(reused.id), libId, rootPath))
                    val revivedIds = repo.idsByFolder(FolderId(reused.id))
                    revivedIds shouldBe listOf(originalId)
                    for (id in revivedIds) repo.reviveById(id)

                    // The book is live again under its ORIGINAL id — no fresh UUID, no stranded tombstone.
                    repo.findById(originalId)?.deletedAt shouldBe null

                    // A subsequent rescan resolves the same id (folder-scoped natural key hits).
                    val rescanId = repo.resolveOrInsert(libId, folderId, analyzedFor("Book A", inode = 1L)).resolved()
                    rescanId shouldBe originalId
                    repo.findById(originalId)?.deletedAt shouldBe null
                }
            }
        }

        test("a genuine file move within a folder keeps the same id") {
            withSqlDatabase {
                val (repo, _, registry) = fixture(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val folder = FolderId("folder-a")

                    val original = repo.resolveOrInsert(libId, folder, analyzedFor("Old/Path", inode = 42L)).resolved()
                    // Same inode, new relative path → the move hint preserves the UUID.
                    val moved = repo.resolveOrInsert(libId, folder, analyzedFor("New/Path", inode = 42L)).resolved()

                    moved shouldBe original
                }
            }
        }

        test("a genuinely new file gets a new id") {
            withSqlDatabase {
                val (repo, _, registry) = fixture(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val folder = FolderId("folder-a")

                    val a = repo.resolveOrInsert(libId, folder, analyzedFor("Book A", inode = 1L)).resolved()
                    val b = repo.resolveOrInsert(libId, folder, analyzedFor("Book B", inode = 2L)).resolved()

                    a shouldNotBe b
                }
            }
        }

        test("two folders sharing a relative path never collide, and the sweep does not alias them") {
            withSqlDatabase {
                val (repo, _, registry) = fixture(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val folderA = FolderId("folder-a")
                    val folderB = FolderId("folder-b")

                    // Same relative path "Book" under two different folders → two distinct books.
                    val idA = repo.resolveOrInsert(libId, folderA, analyzedFor("Book", inode = 1L)).resolved()
                    val idB = repo.resolveOrInsert(libId, folderB, analyzedFor("Book", inode = 2L)).resolved()
                    idA shouldNotBe idB

                    // A sweep that sees BOTH folder-qualified locators tombstones neither.
                    repo.softDeleteAbsentByPaths(
                        libId,
                        seen = setOf(FolderScopedPath(folderA, "Book"), FolderScopedPath(folderB, "Book")),
                    )
                    repo.findById(idA)?.deletedAt shouldBe null
                    repo.findById(idB)?.deletedAt shouldBe null

                    // A sweep that sees only folderA's "Book" tombstones ONLY folderB's — no cross-folder
                    // aliasing on the shared relative path.
                    repo.softDeleteAbsentByPaths(libId, seen = setOf(FolderScopedPath(folderA, "Book")))
                    repo.findById(idA)?.deletedAt shouldBe null
                    repo.findById(idB)?.deletedAt shouldNotBe null
                }
            }
        }

        test("a byte-identical re-add over a tombstoned book clears deleted_at (revival guardrail)") {
            withSqlDatabase {
                val (repo, _, registry) = fixture(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val folder = FolderId("folder-a")

                    val id = repo.resolveOrInsert(libId, folder, analyzedFor("Book A", inode = 1L)).resolved()
                    repo.softDelete(id)
                    repo.findById(id)?.deletedAt shouldNotBe null

                    // Re-scan with byte-identical content: matchesStoredContent would otherwise skip and
                    // leave the tombstone; the guardrail forces a write that clears deleted_at.
                    val again = repo.resolveOrInsert(libId, folder, analyzedFor("Book A", inode = 1L)).resolved()
                    again shouldBe id
                    repo.findById(id)?.deletedAt shouldBe null
                }
            }
        }
    })

// --- Result unwrapping -------------------------------------------------------

private fun AppResult<IngestOutcome>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data.bookId
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

// --- Fixtures ----------------------------------------------------------------

private data class IdentityFixture(
    val repo: BookRepository,
    val folderRepo: LibraryFolderRepository,
    val registry: LibraryRegistry,
)

private fun fixture(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): IdentityFixture {
    val bus = ChangeBus()
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
    val folderRepo = LibraryFolderRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    return IdentityFixture(repo, folderRepo, registry)
}

private fun folderPayload(
    id: FolderId,
    libraryId: LibraryId,
    rootPath: String,
): LibraryFolderSyncPayload =
    LibraryFolderSyncPayload(
        id = id.value,
        libraryId = libraryId.value,
        rootPath = rootPath,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

/**
 * Builds a minimal [AnalyzedBook] anchored at [rootRelPath] with one audio file whose
 * [FileEntry.inode] is [inode] (the book-level inode `resolveOrInsert` uses for move detection).
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
