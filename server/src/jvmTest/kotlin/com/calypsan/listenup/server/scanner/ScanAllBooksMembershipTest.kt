@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.SystemCollectionType
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest

/**
 * Verifies that a scanned book joins exactly ONE system collection, determined by the
 * library's inbox (hold) gate:
 *
 *  - inbox gate OFF (not held) → new book joins ALL_BOOKS, NOT INBOX.
 *  - inbox gate ON  (held)     → new book joins INBOX, NOT ALL_BOOKS.
 *  - re-scan of an already-ingested book → no new membership added (isNew gate).
 *
 * These cases are MUTUALLY EXCLUSIVE. A held book in ALL_BOOKS would be visible to
 * every member (all hold an ALL_BOOKS grant), defeating the inbox quarantine.
 *
 * Drives a real [BookRepository] and [CollectionServiceImpl] against a
 * Flyway-migrated in-memory database — no mocks. Membership is verified through
 * [CollectionRepository.findSystemCollection] + [CollectionBookRepository.findBookIdsForCollection].
 */
class ScanAllBooksMembershipTest :
    FunSpec({

        test("inbox gate OFF: new book joins ALL_BOOKS and NOT INBOX") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.setInboxEnabled("test-library", enabled = false)
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val fx = allBooksFixture(this@withSqlDatabase)
                    val allBooksId = fx.resolveAllBooksId()
                    val inboxId = fx.resolveInboxId()

                    val outcome =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = buildAnalyzedBook("Brandon/Stormlight", inode = 10L),
                            systemCollectionId = allBooksId,
                        )
                    require(outcome is AppResult.Success)

                    // Must be in ALL_BOOKS
                    fx.collectionBookRepo.findBookIdsForCollection(allBooksId) shouldContainExactly
                        listOf(outcome.data.bookId.value)
                    // Must NOT be in INBOX
                    fx.collectionBookRepo.findBookIdsForCollection(inboxId).shouldBeEmpty()
                }
            }
        }

        test("inbox gate ON: new book joins INBOX and NOT ALL_BOOKS") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.setInboxEnabled("test-library", enabled = true)
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val fx = allBooksFixture(this@withSqlDatabase)
                    val allBooksId = fx.resolveAllBooksId()
                    val inboxId = fx.resolveInboxId()

                    val outcome =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = buildAnalyzedBook("Brandon/Mistborn", inode = 11L),
                            systemCollectionId = inboxId,
                        )
                    require(outcome is AppResult.Success)

                    // Must be in INBOX
                    fx.collectionBookRepo.findBookIdsForCollection(inboxId) shouldContainExactly
                        listOf(outcome.data.bookId.value)
                    // Must NOT be in ALL_BOOKS
                    fx.collectionBookRepo.findBookIdsForCollection(allBooksId).shouldBeEmpty()
                }
            }
        }

        test("re-scan of an existing book does not add a new membership") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.setInboxEnabled("test-library", enabled = false)
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val fx = allBooksFixture(this@withSqlDatabase)
                    val allBooksId = fx.resolveAllBooksId()
                    val analyzed = buildAnalyzedBook("Brandon/WayOfKings", inode = 12L)

                    val first =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = analyzed,
                            systemCollectionId = allBooksId,
                        )
                    require(first is AppResult.Success)

                    // Re-scan the same book — should take the UPDATE branch (isNew = false).
                    val second =
                        fx.bookRepo.resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = analyzed,
                            systemCollectionId = allBooksId,
                        )
                    require(second is AppResult.Success)

                    // Still exactly one membership — the update path must not re-add.
                    fx.collectionBookRepo.findBookIdsForCollection(allBooksId) shouldContainExactly
                        listOf(first.data.bookId.value)
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private class AllBooksFixture(
    val bookRepo: BookRepository,
    val collectionBookRepo: CollectionBookRepository,
    val collections: CollectionServiceImpl,
) {
    /** Resolves (creating if needed) the library's ALL_BOOKS collection id. */
    suspend fun resolveAllBooksId(): String {
        val result = collections.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
        require(result is AppResult.Success)
        return result.data.id.value
    }

    /** Resolves (creating if needed) the library's inbox collection id. */
    suspend fun resolveInboxId(): String {
        val inbox = collections.getOrCreateInbox("test-library")
        require(inbox is AppResult.Success)
        return inbox.data.id.value
    }
}

private fun allBooksFixture(dbs: SqlTestDatabases): AllBooksFixture {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val collectionBookRepo =
        CollectionBookRepository(
            db = dbs.sql,
            bus = bus,
            registry = syncRegistry,
            driver = dbs.driver,
        )
    val bookRepo =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = ContributorRepository(dbs.sql, bus, syncRegistry),
            seriesRepository = SeriesRepository(dbs.sql, bus, syncRegistry),
            genreRepository = GenreRepository(dbs.sql, bus, syncRegistry),
            collectionBookRepository = collectionBookRepo,
        )
    val collectionRepo =
        CollectionRepository(
            db = dbs.sql,
            bus = bus,
            registry = syncRegistry,
            driver = dbs.driver,
        )
    val grantRepo =
        CollectionGrantRepository(
            db = dbs.sql,
            bus = bus,
            registry = syncRegistry,
            driver = dbs.driver,
        )
    val collections =
        CollectionServiceImpl(
            collectionRepo = collectionRepo,
            collectionBookRepo = collectionBookRepo,
            grantRepo = grantRepo,
            accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo),
            permissionPolicy = UserPermissionPolicy(dbs.sql),
            bus = bus,
            sql = dbs.sql,
            bookRevisionTouch = FakeBookRevisionTouch(),
            principal = PrincipalProvider { null },
        )
    return AllBooksFixture(bookRepo, collectionBookRepo, collections)
}
