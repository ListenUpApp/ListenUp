@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Covers the **inbox-gate-ON** branch of the mutually-exclusive system-collection
 * membership decision: when a library has `inboxEnabled = true`, a genuinely new
 * book joins INBOX (not ALL_BOOKS).
 *
 * [BookRepository.resolveOrInsert] threads the resolved system-collection id down so
 * that — only for a genuinely NEW book — the `collection_books` membership is written
 * inside the very same transaction as the book row. The firehose evaluates
 * [com.calypsan.listenup.server.api.BookAccessPolicy.canAccess] at delivery, so
 * committing atomically means a member never sees a held book before it is released.
 *
 * Re-running `resolveOrInsert` for the SAME book is an UPDATE — it must NOT add a
 * second membership (only-on-create, idempotent re-scan).
 *
 * The gate-OFF branch (→ ALL_BOOKS, NOT INBOX) is covered by [ScanAllBooksMembershipTest].
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
                            analyzed = buildAnalyzedBook("Sanderson/Way of Kings", inode = 1L),
                            systemCollectionId = inboxId,
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
                    val analyzed = buildAnalyzedBook("Sanderson/Way of Kings", inode = 1L)

                    val first =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = analyzed,
                            systemCollectionId = inboxId,
                        )
                    require(first is AppResult.Success)

                    // Same book, same path → resolve-or-insert takes the UPDATE branch.
                    val second =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = analyzed,
                            systemCollectionId = inboxId,
                        )
                    require(second is AppResult.Success)

                    // Still exactly one membership — the update path must not re-add membership.
                    fx.collectionBookRepo.findBookIdsForCollection(inboxId) shouldContainExactly
                        listOf(first.data.bookId.value)
                }
            }
        }

        test("no system collection id supplied: a NEW book joins no system collection (not the inbox)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val fx = fixture(db)
                    val inboxId = fx.resolveInboxId()

                    // A null systemCollectionId is the "no membership at all" branch: resolveOrInsert
                    // writes the book row but no system-collection junction. (The non-held → ALL_BOOKS
                    // path the scanner actually drives passes the ALL_BOOKS id as systemCollectionId;
                    // that branch is covered by ScanAllBooksMembershipTest.) This test pins the inbox
                    // gate's null contract: with no id supplied, nothing lands in the inbox.
                    val outcome =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = buildAnalyzedBook("Sanderson/Mistborn", inode = 2L),
                            systemCollectionId = null,
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
            genreRepository = GenreRepository(db, bus, syncRegistry),
            collectionBookRepository = collectionBookRepo,
        )
    val collectionRepo = CollectionRepository(db = db, bus = bus, registry = syncRegistry)
    val grantRepo = CollectionGrantRepository(db = db, bus = bus, registry = syncRegistry)
    val collections =
        CollectionServiceImpl(
            collectionRepo = collectionRepo,
            collectionBookRepo = collectionBookRepo,
            grantRepo = grantRepo,
            accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo),
            permissionPolicy = UserPermissionPolicy(db),
            bus = bus,
            db = db,
            bookRevisionTouch = FakeBookRevisionTouch(),
            principal = PrincipalProvider { null },
        )
    return InboxFixture(bookRepo, collectionBookRepo, collections)
}
