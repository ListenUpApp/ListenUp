@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.BookPersisterMetrics
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.LibraryRegistry
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
import io.kotest.matchers.collections.shouldHaveSize
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Verifies the scanner ingest path NEVER auto-adds scanned books to the
 * library's inbox — the Task-10 scan-auto-populate hook was reverted to close
 * the TOCTOU firehose leak (a new book's `book.Created` event became firehose-
 * visible while uncollected → public → leaked to every member, before the
 * separate `addToInbox` transaction could quarantine it).
 *
 * Auto-populate is genuinely separable: the inbox feature remains usable via the
 * admin path ([CollectionServiceImpl.addToInbox] / `releaseBooks` / `listInbox`),
 * which an admin invokes deliberately. Scan-auto-populate becomes a future phase
 * with atomic ingest (membership committed in the same transaction as the book
 * insert, before the `book.Created` publish) designed in from the start.
 *
 * Drives a real [BookPersister] wired to a real [BookRepository] (the ingest
 * port) and a real [CollectionServiceImpl] (the inbox port), against a
 * Flyway-migrated in-memory database — no mocks. The inbox membership is read
 * back through the admin-only [CollectionServiceImpl.listInbox].
 */
class ScannerInboxIngestTest :
    FunSpec({

        test("inbox_enabled=true: a newly-scanned book is NOT auto-added to the inbox (hook reverted)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                setInboxEnabled(db, "test-library", enabled = true)
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val (persister, collections) = fixture(db, this)

                    persister.persist(scanResultFor(book("Sanderson/Way of Kings", inode = 1L)))

                    // Even with inbox_enabled, the scan leaves the book uncollected — no
                    // automatic inbox add, so the firehose-while-public leak cannot arise.
                    val inbox = collections.actingAsAdmin().listInbox("test-library")
                    require(inbox is AppResult.Success)
                    inbox.data.shouldBeEmpty()
                }
            }
        }

        test("inbox_enabled=false: a newly-scanned book is uncollected (public)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                // inbox_enabled defaults to false; do not flip it.
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val (persister, collections) = fixture(db, this)

                    persister.persist(scanResultFor(book("Sanderson/Mistborn", inode = 2L)))

                    val inbox = collections.actingAsAdmin().listInbox("test-library")
                    require(inbox is AppResult.Success)
                    inbox.data.shouldBeEmpty()
                }
            }
        }

        test("admin can still inbox a scanned book deliberately, then release it") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                setInboxEnabled(db, "test-library", enabled = true)
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val (persister, collections) = fixture(db, this)
                    val admin = collections.actingAsAdmin()

                    // Scan a book — lands uncollected (no auto-inbox).
                    persister.persist(scanResultFor(book("Sanderson/Way of Kings", inode = 1L)))
                    val bookId = db.singleBookId()

                    // Admin deliberately inboxes it via the admin path (still supported).
                    require(collections.addToInbox(bookId, "test-library") is AppResult.Success)
                    val afterInbox = admin.listInbox("test-library")
                    require(afterInbox is AppResult.Success)
                    afterInbox.data shouldHaveSize 1

                    // Admin releases it back out to uncollected.
                    val release = admin.releaseBooks("test-library", mapOf(bookId to emptyList()))
                    require(release is AppResult.Success)
                    val afterRelease = admin.listInbox("test-library")
                    require(afterRelease is AppResult.Success)
                    afterRelease.data.shouldBeEmpty()
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private data class InboxFixture(
    val persister: BookPersister,
    val collections: CollectionServiceImpl,
)

/** A [CollectionServiceImpl] bound to an ADMIN principal, for the admin-only inbox reads. */
private fun CollectionServiceImpl.actingAsAdmin(): CollectionServiceImpl = copyWith(adminPrincipal())

private fun adminPrincipal(): PrincipalProvider =
    PrincipalProvider {
        com.calypsan.listenup.server.auth.UserPrincipal(
            com.calypsan.listenup.api.dto.auth
                .UserId("admin"),
            com.calypsan.listenup.api.dto.auth
                .SessionId("session-admin"),
            com.calypsan.listenup.api.dto.auth.UserRole.ADMIN,
        )
    }

private fun fixture(
    db: Database,
    scope: TestScope,
): InboxFixture {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val bookRepo =
        BookRepository(
            db = db,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = ContributorRepository(db, bus, syncRegistry),
            seriesRepository = SeriesRepository(db, bus, syncRegistry),
        )
    val collectionRepo = CollectionRepository(db = db, bus = bus, registry = syncRegistry)
    val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = syncRegistry)
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
    val persister =
        BookPersister(
            ingest = bookRepo,
            libraryRegistry = LibraryRegistry(db, env = mapOf("LISTENUP_LIBRARY_PATH" to "/tmp/test-library")),
            db = db,
            scanResultBus = MutableSharedFlow(),
            eventBus = MutableSharedFlow(),
            scope = scope,
            metrics = BookPersisterMetrics(SimpleMeterRegistry()),
        )
    return InboxFixture(persister, collections)
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

/** Reads back the id of the single book the test scanned in. */
private fun Database.singleBookId(): String =
    transaction(this) {
        BookTable
            .selectAll()
            .single()[BookTable.id]
    }

private fun scanResultFor(vararg books: AnalyzedBook): ScanResult =
    ScanResult(
        correlationId = "test",
        rootPath = "/tmp/test-library",
        books = books.toList(),
        changes = emptyList(),
        errors = emptyList(),
        durationMs = 0L,
        filesWalked = books.size,
        filesSkipped = 0,
        scope = ScanScope.Full,
    )

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
