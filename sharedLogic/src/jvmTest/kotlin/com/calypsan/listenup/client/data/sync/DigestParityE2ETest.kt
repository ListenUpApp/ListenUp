package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.bookTagsDomain
import com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain
import com.calypsan.listenup.client.data.sync.domains.syncDomainCatalog
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.data.sync.domains.listeningEventsDomain
import com.calypsan.listenup.client.data.sync.domains.userStatsDomain
import com.calypsan.listenup.client.data.sync.testing.StubAvatarDownloadRepository
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.assertions.withClue
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

/**
 * Cross-stack parity guard: proves [DigestComputer.compute] produces byte-for-byte
 * identical output to the server's [com.calypsan.listenup.server.sync.SyncableRepository.digest]
 * over the same `(id, revision)` rows.
 *
 * Seeds three series on the real server repository (two live, one soft-deleted so
 * the digest must EXCLUDE the tombstoned row — the F1 tombstone-excluding contract),
 * then computes both the server digest and the client digest and asserts count AND
 * hash equality.
 *
 * If this test ever fails it means the two algorithms have drifted — do not weaken
 * the assertion; instead reconcile the implementations.
 *
 * Also covers four domains with non-trivial id mappings that are still reconcilable:
 * - `user_stats`: id = userId
 * - `listening_events`: id = event UUID
 * - `book_tags`: synthetic `"$bookId:$tagId"`
 * - `collection_books`: synthetic `"$collectionId:$bookId"`
 *
 * For each domain: seed the server, compute the server digest, apply the same rows
 * to the client Room DB via the handler's `onCatchUpItem`, compute the client digest,
 * and assert count AND hash equality. If any domain fails here it is a C1-class bug
 * and must be investigated before opting out.
 */
class DigestParityE2ETest :
    FunSpec({

        test("client DigestComputer produces identical count and hash to server digest") {
            // Stand up a real migrated SQLite database the same way withInMemoryDatabase does.
            val tmp =
                Files.createTempFile("listenup-digest-parity-", ".db").toFile().apply {
                    deleteOnExit()
                }
            // DatabaseFactory.init runs migrations; SeriesRepository reads through a SQLDelight
            // driver over the same already-migrated file.
            val serverDriver =
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).sqlDriver
            val sqlDb = ServerSqlDatabase(serverDriver)

            val repo = SeriesRepository(db = sqlDb, bus = ChangeBus(), registry = SyncRegistry())

            runTest {
                // Seed: two live series + one that is immediately soft-deleted (tombstoned). The
                // tombstone must be EXCLUDED by both the server digest and the parity helper (F1),
                // so the two sides still agree on the LIVE set.
                repo.resolveOrCreate("Mistborn")
                repo.resolveOrCreate("Stormlight")
                val thirdId = repo.resolveOrCreate("Deleted Series")
                repo.softDelete(thirdId)

                // Server digest over all rows with revision ≤ Long.MAX_VALUE.
                val serverDigest = repo.digest(userId = null, cursor = Long.MAX_VALUE)

                // Retrieve the same (id, revision) pairs the server digest read.
                val rows = repo.allIdRevisionsForTest()

                // Client digest over those identical pairs.
                val clientDigest = DigestComputer.compute(cursor = Long.MAX_VALUE, rows = rows)

                // Both sides must agree on count and hash — this is the wire contract.
                clientDigest.count shouldBe serverDigest.count
                clientDigest.hash shouldBe serverDigest.hash
            }
        }

        // ── all-domain wiring guard ───────────────────────────────────────────────────────────

        test("every mirrored domain's client digest path is callable and satisfies the empty-digest contract") {
            // Complements the per-domain server-parity tests (which cover every distinct id-mapping
            // shape) with breadth: EVERY registered mirrored domain must expose a working client
            // digest path. On an empty DB a digest-participating domain (non-null localDigestRows)
            // must produce the canonical empty digest — a wholesale wiring break (wrong table,
            // throwing query, a new domain that forgot its digest wiring) fails here across ALL
            // domains, not just the five seeded above.
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val catalog =
                        syncDomainCatalog(
                            database = db,
                            mapper = BookEntityMapper(),
                            imageStorage = stubImageStorage(),
                            authSession = FakeAuthSession(userId = "digest-parity-user"),
                            avatarDownloadRepository = StubAvatarDownloadRepository(),
                            pingPresence = {},
                            refetchServerInfo = {},
                            refetchPreferences = {},
                        )
                    val registry = ClientSyncDomainRegistry()
                    val runner = RoomTransactionRunner(db)
                    val emptyHash = DigestComputer.compute(cursor = Long.MAX_VALUE, rows = emptyList()).hash

                    for (domain in catalog.mirrored) {
                        val handler = domain.toHandler(runner, registry)
                        val rows = handler.localDigestRows(Long.MAX_VALUE) ?: continue // opt-out domains
                        val digest = DigestComputer.compute(cursor = Long.MAX_VALUE, rows = rows)
                        withClue("domain '${handler.domainName}' empty digest") {
                            digest.count shouldBe 0
                            digest.hash shouldBe emptyHash
                        }
                    }
                }
            } finally {
                db.close()
            }
        }

        // ── user_stats: id = userId ───────────────────────────────────────────────────────────

        test("user_stats: client digest matches server after applying the same rows via onCatchUpItem") {
            val tmp =
                Files.createTempFile("listenup-digest-parity-user-stats-", ".db").toFile().apply {
                    deleteOnExit()
                }
            // DatabaseFactory.init runs migrations; UserStatsRepository reads through a SQLDelight
            // driver over the same already-migrated file.
            val serverDriver =
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).sqlDriver
            val serverSqlDb = ServerSqlDatabase(serverDriver)
            val userStatsRepo =
                UserStatsRepository(db = serverSqlDb, bus = ChangeBus(), registry = SyncRegistry())

            val clientDb = createInMemoryTestDatabase()
            try {
                runTest {
                    val userId = "u1"

                    // Seed two user_stats rows on the server. The id == userId, so we must pass
                    // userId as the write-owner to the user-scoped repository.
                    val stat1 =
                        UserStatsSyncPayload(
                            id = userId,
                            totalSecondsAllTime = 3600L,
                            totalSecondsLast7Days = 600L,
                            totalSecondsLast30Days = 1800L,
                            booksStarted = 5,
                            booksFinished = 2,
                            currentStreakDays = 3,
                            longestStreakDays = 7,
                            lastEventDate = "2026-06-01",
                            revision = 0L,
                            updatedAt = 1000L,
                            createdAt = 500L,
                            deletedAt = null,
                        )
                    userStatsRepo.upsert(stat1, userId = userId)

                    // Server digest — filtered by userId because this is a user-scoped domain.
                    val serverDigest = userStatsRepo.digest(userId = userId, cursor = Long.MAX_VALUE)

                    // Apply via onCatchUpItem using the server-committed payload (pullSince gives
                    // the exact (id, revision) that the server digest hashed).
                    val registry = ClientSyncDomainRegistry()
                    val handler =
                        userStatsDomain(clientDb)
                            .toHandler(RoomTransactionRunner(clientDb), registry)

                    val serverPage = userStatsRepo.pullSince(userId = userId, cursor = 0L, limit = 100)
                    for (item in serverPage.items) {
                        handler.onCatchUpItem(item, isTombstone = false)
                    }

                    val clientRows =
                        checkNotNull(handler.localDigestRows(Long.MAX_VALUE)) {
                            "handler ${handler.domainName} unexpectedly returned null from localDigestRows"
                        }
                    val clientDigest = DigestComputer.compute(cursor = Long.MAX_VALUE, rows = clientRows)

                    clientDigest.count shouldBe serverDigest.count
                    clientDigest.hash shouldBe serverDigest.hash
                }
            } finally {
                clientDb.close()
            }
        }

        // ── listening_events: id = event UUID ────────────────────────────────────────────────

        test("listening_events: client digest matches server after applying the same rows via onCatchUpItem") {
            val tmp =
                Files.createTempFile("listenup-digest-parity-listening-events-", ".db").toFile().apply {
                    deleteOnExit()
                }
            // DatabaseFactory.init runs migrations; ListeningEventRepository reads through a SQLDelight
            // driver over the same already-migrated file.
            val serverDriver =
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).sqlDriver
            val serverSqlDb = ServerSqlDatabase(serverDriver)
            // null userStatsUpdater is safe here — stats accrual is not under test.
            val listeningEventRepo =
                ListeningEventRepository(db = serverSqlDb, bus = ChangeBus(), registry = SyncRegistry())

            val clientDb = createInMemoryTestDatabase()
            try {
                runTest {
                    val userId = "u1"

                    // Seed two listening events on the server.
                    val event1 =
                        ListeningEventSyncPayload(
                            id = "event-1",
                            bookId = "book-1",
                            startPositionMs = 0L,
                            endPositionMs = 60_000L,
                            startedAt = 1000L,
                            endedAt = 61_000L,
                            playbackSpeed = 1.0f,
                            tz = "UTC",
                            deviceLabel = "Test",
                            revision = 0L,
                            updatedAt = 1000L,
                            createdAt = 1000L,
                            deletedAt = null,
                        )
                    val event2 =
                        ListeningEventSyncPayload(
                            id = "event-2",
                            bookId = "book-1",
                            startPositionMs = 60_000L,
                            endPositionMs = 120_000L,
                            startedAt = 2000L,
                            endedAt = 62_000L,
                            playbackSpeed = 1.5f,
                            tz = "UTC",
                            deviceLabel = "Test",
                            revision = 0L,
                            updatedAt = 2000L,
                            createdAt = 2000L,
                            deletedAt = null,
                        )
                    listeningEventRepo.upsert(event1, userId = userId)
                    listeningEventRepo.upsert(event2, userId = userId)

                    // Server digest for user u1.
                    val serverDigest = listeningEventRepo.digest(userId = userId, cursor = Long.MAX_VALUE)

                    // Apply the same events to the client via onCatchUpItem with the server-committed
                    // revisions (read back via pullSince to get the real revision values).
                    val registry = ClientSyncDomainRegistry()
                    val handler =
                        listeningEventsDomain(clientDb, FakeAuthSession(userId))
                            .toHandler(RoomTransactionRunner(clientDb), registry)

                    val serverPage = listeningEventRepo.pullSince(userId = userId, cursor = 0L, limit = 100)
                    for (item in serverPage.items) {
                        handler.onCatchUpItem(item, isTombstone = false)
                    }

                    val clientRows =
                        checkNotNull(handler.localDigestRows(Long.MAX_VALUE)) {
                            "handler ${handler.domainName} unexpectedly returned null from localDigestRows"
                        }
                    val clientDigest = DigestComputer.compute(cursor = Long.MAX_VALUE, rows = clientRows)

                    clientDigest.count shouldBe serverDigest.count
                    clientDigest.hash shouldBe serverDigest.hash
                }
            } finally {
                clientDb.close()
            }
        }

        // ── book_tags: synthetic "$bookId:$tagId" ─────────────────────────────────────────────

        test("book_tags: client digest matches server after applying the same rows via onCatchUpItem") {
            val tmp =
                Files.createTempFile("listenup-digest-parity-book-tags-", ".db").toFile().apply {
                    deleteOnExit()
                }
            // DatabaseFactory.init runs migrations; BookTagRepository reads through a SQLDelight
            // driver over the same already-migrated file.
            val serverDriver =
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).sqlDriver
            val serverSqlDb = ServerSqlDatabase(serverDriver)
            val bookTagRepo = BookTagRepository(db = serverSqlDb, bus = ChangeBus(), registry = SyncRegistry())

            val clientDb = createInMemoryTestDatabase()
            try {
                runTest {
                    // Seed parent rows required by the book_tags FK constraints (foreign_keys=ON).
                    // books.library_id REFERENCES libraries(id), so we need a library row first.
                    val now = System.currentTimeMillis()
                    serverDriver.execute(
                        null,
                        "INSERT INTO libraries(id, name, created_at, updated_at, revision) " +
                            "VALUES ('lib', 'Test Library', $now, $now, 0)",
                        0,
                    )
                    // Minimal books rows — include all NOT NULL columns.
                    for (bid in listOf("book-1", "book-2", "book-3")) {
                        serverDriver.execute(
                            null,
                            "INSERT INTO books(id, library_id, folder_id, title, " +
                                "total_duration, root_rel_path, scanned_at, revision, created_at, updated_at) " +
                                "VALUES ('$bid', 'lib', 'folder', 'Book $bid', 0, '$bid', $now, 0, $now, $now)",
                            0,
                        )
                    }
                    // Minimal tags rows.
                    for ((tid, slug) in listOf("tag-a" to "ta", "tag-b" to "tb", "tag-c" to "tc")) {
                        serverDriver.execute(
                            null,
                            "INSERT INTO tags(id, name, slug, revision, created_at, updated_at) " +
                                "VALUES ('$tid', '$tid', '$slug', 0, $now, $now)",
                            0,
                        )
                    }

                    // Seed three live book-tag junctions (no tombstones — the parity test targets
                    // the id-mapping contract, not the tombstone catch-up path).
                    val payload1 =
                        BookTagSyncPayload(bookId = "book-1", tagId = "tag-a", createdAt = 1000L, revision = 0L)
                    val payload2 =
                        BookTagSyncPayload(bookId = "book-2", tagId = "tag-b", createdAt = 2000L, revision = 0L)
                    val payload3 =
                        BookTagSyncPayload(bookId = "book-3", tagId = "tag-c", createdAt = 3000L, revision = 0L)
                    bookTagRepo.upsert(payload1)
                    bookTagRepo.upsert(payload2)
                    bookTagRepo.upsert(payload3)

                    // Server digest (global domain — no userId).
                    val serverDigest = bookTagRepo.digest(userId = null, cursor = Long.MAX_VALUE)

                    // Apply the same live rows to the client via onCatchUpItem.
                    val registry = ClientSyncDomainRegistry()
                    val handler =
                        bookTagsDomain(database = clientDb).toHandler(
                            transactionRunner = RoomTransactionRunner(clientDb),
                            registry = registry,
                        )

                    val serverPage = bookTagRepo.pullSince(userId = null, cursor = 0L, limit = 100)
                    for (item in serverPage.items) {
                        handler.onCatchUpItem(item, isTombstone = false)
                    }

                    val clientRows =
                        checkNotNull(handler.localDigestRows(Long.MAX_VALUE)) {
                            "handler ${handler.domainName} unexpectedly returned null from localDigestRows"
                        }
                    val clientDigest = DigestComputer.compute(cursor = Long.MAX_VALUE, rows = clientRows)

                    clientDigest.count shouldBe serverDigest.count
                    clientDigest.hash shouldBe serverDigest.hash
                }
            } finally {
                clientDb.close()
            }
        }

        // ── collection_books: synthetic "$collectionId:$bookId" ──────────────────────────────

        test("collection_books: client digest matches server after applying the same rows via onCatchUpItem") {
            val tmp =
                Files.createTempFile("listenup-digest-parity-collection-books-", ".db").toFile().apply {
                    deleteOnExit()
                }
            val serverDriver =
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}")).sqlDriver
            val collectionBookRepo =
                CollectionBookRepository(
                    db = ServerSqlDatabase(serverDriver),
                    bus = ChangeBus(),
                    registry = SyncRegistry(),
                    driver = serverDriver,
                )

            val clientDb = createInMemoryTestDatabase()
            try {
                runTest {
                    // Seed parent rows required by the collection_books FK constraints (foreign_keys=ON).
                    val now = System.currentTimeMillis()
                    // Library row required by CollectionsTable.libraryId FK.
                    serverDriver.execute(
                        null,
                        "INSERT INTO libraries(id, name, created_at, updated_at, revision) " +
                            "VALUES ('lib', 'Test Library', $now, $now, 0)",
                        0,
                    )
                    // Collections rows.
                    for (cid in listOf("col-1", "col-2")) {
                        serverDriver.execute(
                            null,
                            "INSERT INTO collections(id, library_id, owner_id, name, revision, created_at, updated_at) " +
                                "VALUES ('$cid', 'lib', 'u1', 'Collection $cid', 0, $now, $now)",
                            0,
                        )
                    }
                    // Books rows — include all NOT NULL columns.
                    for (bid in listOf("book-1", "book-2", "book-3")) {
                        serverDriver.execute(
                            null,
                            "INSERT INTO books(id, library_id, folder_id, title, " +
                                "total_duration, root_rel_path, scanned_at, revision, created_at, updated_at) " +
                                "VALUES ('$bid', 'lib', 'folder', 'Book $bid', 0, '$bid', $now, 0, $now, $now)",
                            0,
                        )
                    }

                    // Seed three live collection-book junctions (no tombstones — the parity test
                    // targets the id-mapping contract, not the tombstone catch-up path).
                    val payload1 =
                        CollectionBookSyncPayload(
                            collectionId = "col-1",
                            bookId = "book-1",
                            createdAt = 1000L,
                            revision = 0L,
                        )
                    val payload2 =
                        CollectionBookSyncPayload(
                            collectionId = "col-1",
                            bookId = "book-2",
                            createdAt = 2000L,
                            revision = 0L,
                        )
                    val payload3 =
                        CollectionBookSyncPayload(
                            collectionId = "col-2",
                            bookId = "book-3",
                            createdAt = 3000L,
                            revision = 0L,
                        )
                    collectionBookRepo.upsert(payload1)
                    collectionBookRepo.upsert(payload2)
                    collectionBookRepo.upsert(payload3)

                    // Server digest (global domain — no userId).
                    val serverDigest = collectionBookRepo.digest(userId = null, cursor = Long.MAX_VALUE)

                    // Apply the same live rows to the client via onCatchUpItem.
                    val registry = ClientSyncDomainRegistry()
                    val handler =
                        collectionBooksDomain(clientDb)
                            .toHandler(RoomTransactionRunner(clientDb), registry)

                    val serverPage = collectionBookRepo.pullSince(userId = null, cursor = 0L, limit = 100)
                    for (item in serverPage.items) {
                        handler.onCatchUpItem(item, isTombstone = false)
                    }

                    val clientRows =
                        checkNotNull(handler.localDigestRows(Long.MAX_VALUE)) {
                            "handler ${handler.domainName} unexpectedly returned null from localDigestRows"
                        }
                    val clientDigest = DigestComputer.compute(cursor = Long.MAX_VALUE, rows = clientRows)

                    clientDigest.count shouldBe serverDigest.count
                    clientDigest.hash shouldBe serverDigest.hash
                }
            } finally {
                clientDb.close()
            }
        }
    })
