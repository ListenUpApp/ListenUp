package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.handlers.ActiveSessionSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.BookTagSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.CollectionBookSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.CollectionShareSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.CollectionSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.ContributorSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.GenreSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.LibraryFolderSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.LibrarySyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.ListeningEventSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.PlaybackPositionSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.SeriesSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.UserStatsSyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Pins the exact set of production handlers that opt out of digest reconciliation.
 *
 * A handler opts out by returning `null` from [SyncDomainHandler.localDigestRows].
 * An empty DB makes a reconcilable domain return `emptyList()` (non-null), and an
 * opted-out domain return `null` — so "opted out" == returns null against an empty DB.
 *
 * If a future domain silently can't be fingerprinted (another C1-class bug — server uses
 * an id the client never stores), this test will fail the build instead of the domain
 * looping forever on every reconnect.
 */
class DigestOptOutSetTest :
    FunSpec({

        test("exactly active_sessions and playback_positions opt out of digest reconciliation") {
            runTest {
                val clientDb = createInMemoryTestDatabase()
                try {
                    val registry = ClientSyncDomainRegistry()
                    val txRunner = RoomTransactionRunner(clientDb)

                    // Register all 15 production handlers — mirrors the clientSyncRenovationModule
                    // Koin wiring so this test tracks production exactly.
                    TagSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    BookTagSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    ActiveSessionSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    BookSyncDomainHandler(
                        database = clientDb,
                        mapper = BookEntityMapper(),
                        transactionRunner = txRunner,
                        registry = registry,
                    )
                    ContributorSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    SeriesSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    GenreSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    PlaybackPositionSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    ListeningEventSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    UserStatsSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    LibrarySyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    LibraryFolderSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    CollectionSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    CollectionBookSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)
                    CollectionShareSyncDomainHandler(database = clientDb, transactionRunner = txRunner, registry = registry)

                    // Collect the domains whose handler returns null from localDigestRows against
                    // an empty DB. An empty DB ensures reconcilable domains return emptyList() (non-null)
                    // and opted-out domains return null — so null == opted out.
                    val optedOut =
                        registry
                            .registeredDomains()
                            .filter { domain ->
                                val handler = registry.lookup(domain)!!
                                handler.localDigestRows(Long.MAX_VALUE) == null
                            }.toSet()

                    optedOut shouldBe setOf("active_sessions", "playback_positions")
                } finally {
                    clientDb.close()
                }
            }
        }
    })
