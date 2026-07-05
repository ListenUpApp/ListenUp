package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import app.cash.sqldelight.db.SqlDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

/**
 * F1 — the deferred correctness item from the sync-core reviews.
 *
 * For an access-gated domain the client digest is tombstone-INCLUSIVE while the server's
 * *member* digest is access-filtered and therefore tombstone-EXCLUSIVE (a deleted or
 * inaccessible row falls out of `accessibleBookIdsSql`, which requires `deleted_at IS NULL`).
 * After the client tombstones a row (via a delivered deletion, or the `AccessGate` prune on
 * revoke) the two digests can never converge, so `reconcileAll` re-derives the whole domain
 * (`repairDrift → catchUpTransient`) on EVERY reconcile edge, forever, for any member who ever
 * witnessed a deletion.
 *
 * These tests reproduce non-convergence on `books` (the reference gated domain) against the
 * REAL server repository + access policy, and prove:
 *  1. a delivered deletion converges (client digest EQUALS the server member digest);
 *  2. a share revoke (`AccessGate` prune) converges the same way;
 *  3. the access-filtered catch-up delivers the tombstone for a MISSED live deletion, so a
 *     member who was offline during the delete can still learn to remove the row and converge.
 *
 * The convergence assertion is `clientDigest == serverMemberDigest` at the same cursor — the
 * exact comparison `SyncReconciler.reconcileOne` performs, so equality means `reconcileAll` is a
 * stable no-op (no `catchUpTransient`) on the next pass.
 */
class AccessGatedDigestConvergenceE2ETest :
    FunSpec({

        test("delivered deletion: a member's client digest converges with the server member digest") {
            withBooksServerAndClient { server, clientDb ->
                runTest {
                    val member = "m1"
                    val role = UserRole.MEMBER
                    server.seedAccessibleBook(bookId = "b1", collectionId = "c1", ownerId = member)

                    val filter = server.policy.accessibleBookIdsSql(member, role).shouldNotBeNull()
                    val handler =
                        booksDomain(clientDb, BookEntityMapper(), stubImageStorage())
                            .toHandler(RoomTransactionRunner(clientDb), ClientSyncDomainRegistry())

                    // Member mirrors b1 (live) via the access-filtered catch-up.
                    val page = server.bookRepo.pullSince(member, cursor = 0L, limit = 100, extraWhere = filter)
                    page.items.forEach { handler.onCatchUpItem(it, isTombstone = it.deletedAt != null) }
                    clientDb.bookDao().getById(BookId("b1")).shouldNotBeNull()

                    // Book deleted server-side; the tombstone reaches the member (live firehose is
                    // tombstone-ungated) and the member applies it locally.
                    server.bookRepo.softDelete(BookId("b1"))
                    handler.onCatchUpItem(server.tombstonePayloadOf("b1"), isTombstone = true)
                    // getById returns rows regardless of tombstone state (point lookups ignore
                    // soft-deletes by design), so assert the tombstone via deletedAt.
                    clientDb
                        .bookDao()
                        .getById(BookId("b1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()

                    // Convergence: the client digest must EQUAL the server member digest at the same
                    // cursor — i.e. reconcileAll would find no drift and do NO catch-up on the next pass.
                    val clientDigest =
                        DigestComputer.compute(Long.MAX_VALUE, handler.localDigestRows(Long.MAX_VALUE).shouldNotBeNull())
                    val serverMemberDigest = server.bookRepo.digest(member, Long.MAX_VALUE, filter)

                    clientDigest.count shouldBe serverMemberDigest.count
                    clientDigest.hash shouldBe serverMemberDigest.hash
                }
            }
        }

        test("revoke: after an AccessGate prune, the client digest converges with the server member digest") {
            withBooksServerAndClient { server, clientDb ->
                runTest {
                    val member = "m1"
                    val role = UserRole.MEMBER
                    server.seedAccessibleBook(bookId = "b1", collectionId = "c1", ownerId = member)

                    val filterBefore = server.policy.accessibleBookIdsSql(member, role).shouldNotBeNull()
                    val handler =
                        booksDomain(clientDb, BookEntityMapper(), stubImageStorage())
                            .toHandler(RoomTransactionRunner(clientDb), ClientSyncDomainRegistry())

                    val page = server.bookRepo.pullSince(member, cursor = 0L, limit = 100, extraWhere = filterBefore)
                    page.items.forEach { handler.onCatchUpItem(it, isTombstone = it.deletedAt != null) }
                    clientDb.bookDao().getById(BookId("b1")).shouldNotBeNull()

                    // Share revoked: the collection membership is removed, so b1 leaves the member's
                    // accessible set (the book itself stays live server-side). The AccessGate prune
                    // tombstones the now-inaccessible local row (exactly bookDao().tombstoneByIds).
                    server.revokeMembership(bookId = "b1", collectionId = "c1")
                    clientDb.bookDao().tombstoneByIds(listOf("b1"), now = 999L)
                    // getById returns rows regardless of tombstone state (point lookups ignore
                    // soft-deletes by design), so assert the tombstone via deletedAt.
                    clientDb
                        .bookDao()
                        .getById(BookId("b1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()

                    val filterAfter = server.policy.accessibleBookIdsSql(member, role).shouldNotBeNull()
                    val clientDigest =
                        DigestComputer.compute(Long.MAX_VALUE, handler.localDigestRows(Long.MAX_VALUE).shouldNotBeNull())
                    val serverMemberDigest = server.bookRepo.digest(member, Long.MAX_VALUE, filterAfter)

                    clientDigest.count shouldBe serverMemberDigest.count
                    clientDigest.hash shouldBe serverMemberDigest.hash
                }
            }
        }

        test("missed live deletion: the access-filtered catch-up delivers the tombstone, and the member converges") {
            withBooksServerAndClient { server, clientDb ->
                runTest {
                    val member = "m1"
                    val role = UserRole.MEMBER
                    server.seedAccessibleBook(bookId = "b1", collectionId = "c1", ownerId = member)

                    val filter = server.policy.accessibleBookIdsSql(member, role).shouldNotBeNull()
                    val handler =
                        booksDomain(clientDb, BookEntityMapper(), stubImageStorage())
                            .toHandler(RoomTransactionRunner(clientDb), ClientSyncDomainRegistry())

                    // Member mirrors b1 live, then goes OFFLINE.
                    server.bookRepo
                        .pullSince(member, cursor = 0L, limit = 100, extraWhere = filter)
                        .items
                        .forEach { handler.onCatchUpItem(it, isTombstone = it.deletedAt != null) }
                    clientDb.bookDao().getById(BookId("b1")).shouldNotBeNull()

                    // b1 deleted while the member is offline — the live tombstone is NEVER delivered.
                    server.bookRepo.softDelete(BookId("b1"))

                    // Reconcile catch-up: a transient access-filtered pull from cursor 0. It MUST carry
                    // the b1 tombstone so the member can remove the row and converge — even though b1 is
                    // no longer "accessible" (a tombstone leaks no content, mirroring the firehose rule).
                    val catchUp = server.bookRepo.pullSince(member, cursor = 0L, limit = 100, extraWhere = filter)
                    val tombstone = catchUp.items.firstOrNull { it.id == "b1" && it.deletedAt != null }
                    tombstone.shouldNotBeNull()

                    catchUp.items.forEach { handler.onCatchUpItem(it, isTombstone = it.deletedAt != null) }
                    // getById returns rows regardless of tombstone state (point lookups ignore
                    // soft-deletes by design), so assert the tombstone via deletedAt.
                    clientDb
                        .bookDao()
                        .getById(BookId("b1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()

                    val clientDigest =
                        DigestComputer.compute(Long.MAX_VALUE, handler.localDigestRows(Long.MAX_VALUE).shouldNotBeNull())
                    val serverMemberDigest = server.bookRepo.digest(member, Long.MAX_VALUE, filter)
                    clientDigest.count shouldBe serverMemberDigest.count
                    clientDigest.hash shouldBe serverMemberDigest.hash
                }
            }
        }
    })

/** The server side wired for a single convergence run: the real books repo + access policy over a migrated DB. */
private class BooksServerHarness(
    val bookRepo: BookRepository,
    val policy: BookAccessPolicy,
    val driver: SqlDriver,
) {
    private var revisionSeed = 1L

    /**
     * Seeds a live book [bookId] that member-owner [ownerId] can see: a member-owned live
     * collection [collectionId] containing the book (the pure-union visibility rule). Raw SQL
     * because the tables are `internal`; mirrors the DigestParityE2ETest seeding style.
     */
    fun seedAccessibleBook(
        bookId: String,
        collectionId: String,
        ownerId: String,
    ) {
        val now = System.currentTimeMillis()
        driver.execute(
            null,
            "INSERT OR IGNORE INTO libraries(id, name, created_at, updated_at, revision) " +
                "VALUES ('lib', 'Test Library', $now, $now, 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO books(id, library_id, folder_id, title, total_duration, root_rel_path, " +
                "scanned_at, revision, created_at, updated_at) " +
                "VALUES ('$bookId', 'lib', 'folder', 'Book $bookId', 0, '$bookId', $now, ${revisionSeed++}, $now, $now)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO collections(id, library_id, owner_id, name, type, created_at, updated_at, revision) " +
                "VALUES ('$collectionId', 'lib', '$ownerId', 'Collection', 'NORMAL', $now, $now, ${revisionSeed++})",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO collection_books(id, collection_id, book_id, created_at, updated_at, revision) " +
                "VALUES ('$collectionId:$bookId', '$collectionId', '$bookId', $now, $now, ${revisionSeed++})",
            0,
        )
    }

    /** Removes the book→collection membership (a share revoke) so the book leaves the member's accessible set. */
    fun revokeMembership(
        bookId: String,
        collectionId: String,
    ) {
        val now = System.currentTimeMillis()
        driver.execute(
            null,
            "UPDATE collection_books SET deleted_at = $now WHERE collection_id = '$collectionId' AND book_id = '$bookId'",
            0,
        )
    }

    /** The current server payload for [bookId], tombstone included — read via the unfiltered (admin) pull. */
    suspend fun tombstonePayloadOf(bookId: String): BookSyncPayload =
        bookRepo
            .pullSince(userId = null, cursor = 0L, limit = 500, extraWhere = null)
            .items
            .first { it.id == bookId }
}

private fun withBooksServerAndClient(block: (BooksServerHarness, ListenUpDatabase) -> Unit) {
    val tmp =
        Files.createTempFile("listenup-f1-convergence-", ".db").toFile().apply { deleteOnExit() }
    DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
    val driver = DriverFactory().createDriver(tmp.absolutePath)
    val sqlDb = ServerSqlDatabase(driver)
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val bookRepo =
        BookRepository(
            db = sqlDb,
            bus = bus,
            registry = registry,
            driver = driver,
            contributorRepository = ContributorRepository(sqlDb, bus, SyncRegistry()),
            seriesRepository = SeriesRepository(sqlDb, bus, SyncRegistry()),
            genreRepository = GenreRepository(sqlDb, bus, SyncRegistry()),
        )
    val policy = BookAccessPolicy(sqlDb, driver)
    val clientDb = createInMemoryTestDatabase()
    try {
        block(BooksServerHarness(bookRepo, policy, driver), clientDb)
    } finally {
        clientDb.close()
    }
}
