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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Verifies the scanner ingest path auto-adds NEWLY-scanned books to the
 * library's inbox when (and only when) the library's `inbox_enabled` flag is
 * set — Collections-1b Task 10.
 *
 * Drives a real [BookPersister] wired to a real [BookRepository] (the ingest
 * port) and a real [CollectionServiceImpl] (the inbox port), against a
 * Flyway-migrated in-memory database — no mocks. The inbox membership is read
 * back through the admin-only [CollectionServiceImpl.listInbox].
 */
class ScannerInboxIngestTest :
    FunSpec({

        test("inbox_enabled=true: a newly-scanned book lands in the inbox") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                setInboxEnabled(db, "test-library", enabled = true)
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val (persister, collections) = fixture(db, this)

                    persister.persist(scanResultFor(book("Sanderson/Way of Kings", inode = 1L)))

                    val inbox = collections.actingAsAdmin().listInbox("test-library")
                    require(inbox is AppResult.Success)
                    inbox.data shouldHaveSize 1
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

        test("inbox_enabled=true: a re-scanned (existing) book is NOT re-added to the inbox") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                setInboxEnabled(db, "test-library", enabled = true)
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val (persister, collections) = fixture(db, this)
                    val admin = collections.actingAsAdmin()

                    val book = book("Sanderson/Way of Kings", inode = 1L)

                    // First scan: lands in the inbox.
                    persister.persist(scanResultFor(book))
                    val afterFirst = admin.listInbox("test-library")
                    require(afterFirst is AppResult.Success)
                    afterFirst.data shouldHaveSize 1

                    // Admin releases it (out of the inbox → uncollected).
                    val releasedId = afterFirst.data.single().value
                    val release = admin.releaseBooks("test-library", mapOf(releasedId to emptyList()))
                    require(release is AppResult.Success)

                    // Re-scan the SAME book: it must NOT be re-added to the inbox —
                    // a released book stays released.
                    persister.persist(scanResultFor(book))
                    val afterRescan = admin.listInbox("test-library")
                    require(afterRescan is AppResult.Success)
                    afterRescan.data.shouldBeEmpty()
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
            scope = scope,
            metrics = BookPersisterMetrics(SimpleMeterRegistry()),
            inboxIngest = collections,
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
