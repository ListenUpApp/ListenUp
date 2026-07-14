package com.calypsan.listenup.client.data.sync.testing

import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.NoOutboxInFlight
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.domains.SyncDomainCatalog
import com.calypsan.listenup.client.data.sync.domains.syncDomainCatalog
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage

/**
 * Registers the REAL production catalog's handlers into [registry] — the one place
 * test harnesses obtain sync-domain handlers from. Replaces per-harness hand-lists,
 * so adding a domain to [syncDomainCatalog] automatically covers every harness and
 * a migration touches ZERO harness call sites (wrinkles ledger #4).
 *
 * [exclude] skips domains a harness registers its own double for (the registry
 * throws on a second instance per name — e.g. `RecordingTagSyncDomainHandler`).
 *
 * [authSession]'s default `userId` is arbitrary — it only stamps locally-written
 * `listening_events` rows and never gates inbound apply. A harness whose server
 * bearer identity matters (so those local writes must match it) should override it,
 * as `WithClientSyncEngineAgainstServer` does with `FakeAuthSession()` (`"u1"`).
 */
internal fun registerTestSyncDomains(
    db: ListenUpDatabase,
    registry: ClientSyncDomainRegistry,
    transactionRunner: TransactionRunner = RoomTransactionRunner(db),
    authSession: AuthSession = FakeAuthSession(userId = "test-user"),
    exclude: Set<String> = emptySet(),
    inFlightOutbox: OutboxInFlightQuery = NoOutboxInFlight,
): SyncDomainCatalog {
    val catalog =
        syncDomainCatalog(
            database = db,
            mapper = BookEntityMapper(),
            imageStorage = stubImageStorage(),
            authSession = authSession,
            avatarDownloadRepository = StubAvatarDownloadRepository(),
            pingPresence = {},
            pingCampfires = {},
            refetchServerInfo = {},
            refetchPreferences = {},
        )
    catalog.mirrored
        .filterNot { it.key.name in exclude }
        .forEach { it.toHandler(transactionRunner, registry, inFlightOutbox) }
    return catalog
}

/** No-op [AvatarDownloadRepository] double shared by every test harness that pulls in the public-profiles domain. */
internal class StubAvatarDownloadRepository : AvatarDownloadRepository {
    override fun queueAvatarDownload(userId: String) = Unit

    override fun queueAvatarForceRefresh(userId: String) = Unit

    override suspend fun deleteAvatar(userId: String) = Unit
}
