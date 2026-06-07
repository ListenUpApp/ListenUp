@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionShareRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Verifies the atomic inbox-membership seam in the book-insert transaction.
 *
 * When a library is inbox-enabled and the scan ingest resolves the inbox collection,
 * [BookRepository.resolveOrInsert] threads its id down so that — only for a genuinely
 * NEW book — the book→inbox `collection_books` membership is written inside the very
 * same transaction as the book row. The firehose evaluates [com.calypsan.listenup.server.api.BookAccessPolicy.canAccess]
 * at delivery, so committing membership atomically with the insert means a member
 * never sees the book (it is already in the admin-only inbox before the `book.Created`
 * publish is visible). This closes the TOCTOU leak the old scan-hook revert guarded.
 *
 * Re-running `resolveOrInsert` for the SAME book is an UPDATE — it must NOT add a
 * second membership (only-on-create, idempotent re-scan).
 *
 * Drives a real [BookRepository] (the ingest port) and a real [CollectionServiceImpl]
 * (the inbox resolver) against a Flyway-migrated in-memory database — no mocks.
 * Membership is read back through [CollectionBookRepository.findBookIdsForCollection].
 */
class ScannerInboxIngestTest :
    FunSpec({

        test("inbox_enabled + inbox id: a NEW book is committed into the inbox") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                setInboxEnabled(db, "test-library", enabled = true)
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val fx = fixture(db)
                    val inboxId = fx.resolveInboxId()

                    val outcome =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = book("Sanderson/Way of Kings", inode = 1L),
                            inboxCollectionId = inboxId,
                        )
                    require(outcome is AppResult.Success)

                    fx.collectionBookRepo.findBookIdsForCollection(inboxId) shouldContainExactly
                        listOf(outcome.data.bookId.value)
                }
            }
        }

        test("re-resolving the SAME book (update) does not add a second membership") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                setInboxEnabled(db, "test-library", enabled = true)
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val fx = fixture(db)
                    val inboxId = fx.resolveInboxId()
                    val analyzed = book("Sanderson/Way of Kings", inode = 1L)

                    val first =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = analyzed,
                            inboxCollectionId = inboxId,
                        )
                    require(first is AppResult.Success)

                    // Same book, same path → resolve-or-insert takes the UPDATE branch.
                    val second =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = analyzed,
                            inboxCollectionId = inboxId,
                        )
                    require(second is AppResult.Success)

                    // Still exactly one membership — the update path must not re-inbox.
                    fx.collectionBookRepo.findBookIdsForCollection(inboxId) shouldContainExactly
                        listOf(first.data.bookId.value)
                }
            }
        }

        test("no inbox id supplied: a NEW book is uncollected (public)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val fx = fixture(db)
                    val inboxId = fx.resolveInboxId()

                    val outcome =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = book("Sanderson/Mistborn", inode = 2L),
                            inboxCollectionId = null,
                        )
                    require(outcome is AppResult.Success)

                    fx.collectionBookRepo.findBookIdsForCollection(inboxId).shouldBeEmpty()
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private class InboxFixture(
    val bookRepo: BookRepository,
    val collectionBookRepo: CollectionBookRepository,
    val collections: CollectionServiceImpl,
) {
    /** Resolves (creating if needed) the library's inbox collection id, as the scan path will. */
    suspend fun resolveInboxId(): String {
        val inbox = collections.getOrCreateInbox("test-library")
        require(inbox is AppResult.Success)
        return inbox.data.id.value
    }
}

private fun fixture(db: Database): InboxFixture {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = syncRegistry)
    val bookRepo =
        BookRepository(
            db = db,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = ContributorRepository(db, bus, syncRegistry),
            seriesRepository = SeriesRepository(db, bus, syncRegistry),
            collectionBookRepository = collectionBookRepo,
        )
    val collectionRepo = CollectionRepository(db = db, bus = bus, registry = syncRegistry)
    val shareRepo = CollectionShareRepository(db = db, bus = bus, registry = syncRegistry)
    val collections =
        CollectionServiceImpl(
            collectionRepo = collectionRepo,
            collectionBookRepo = collectionBookRepo,
            shareRepo = shareRepo,
            accessPolicy = CollectionAccessPolicy(collectionRepo, shareRepo),
            permissionPolicy = UserPermissionPolicy(db),
            bus = bus,
            db = db,
            principal = PrincipalProvider { null },
        )
    return InboxFixture(bookRepo, collectionBookRepo, collections)
}

private fun setInboxEnabled(
    db: Database,
    libraryId: String,
    enabled: Boolean,
) {
    transaction(db) {
        LibraryTable.update({ LibraryTable.id eq libraryId }) { it[inboxEnabled] = enabled }
    }
}

private fun book(
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
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file)),
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
    )
}
