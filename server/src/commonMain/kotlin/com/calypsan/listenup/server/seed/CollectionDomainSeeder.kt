package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.uuid.Uuid
import kotlin.time.Clock

private val logger = loggerFor<CollectionDomainSeeder>()

/** Display name of the single demo collection seeded for the demo profile. */
private const val DEMO_COLLECTION_NAME = "Favourites"

/**
 * Seeds the demo profile's collections: the per-library inbox (a system collection every
 * library should have so freshly-ingested books have somewhere to land) plus one demo
 * collection ("Favourites") owned by the demo user.
 *
 * The inbox is materialised through [CollectionServiceImpl.getOrCreateInbox] — the real
 * system write-path — so the seeded row is indistinguishable from a lazily-created one.
 * The demo collection is written through [CollectionRepository.upsert], the collections
 * domain's own write-path.
 *
 * Idempotency rests on [CollectionRepository.findInboxForLibrary]: when the demo library
 * already has an inbox, the seeder considers itself done and does not re-seed. A failure
 * from either write is logged and swallowed so a second `seed()` call never throws.
 *
 * Runs at order 31 — just after [TagDomainSeeder] (order 30). Like tags it references no
 * scan-derived state; it only needs the demo library (order 5) and demo user (order 0) to
 * already exist, both of which run earlier.
 */
internal class CollectionDomainSeeder(
    private val sql: ListenUpDatabase,
    private val collectionRepo: CollectionRepository,
    private val collectionService: CollectionServiceImpl,
    private val clock: Clock = Clock.System,
) : DomainSeeder {
    override val domainName: String = "collections"

    override val order: Int = 31

    /** True when the demo library already has an inbox (or there is no demo library yet). */
    override suspend fun isAlreadySeeded(): Boolean {
        val libraryId = demoLibraryId() ?: return false
        return collectionRepo.findInboxForLibrary(libraryId) != null
    }

    override suspend fun seed() {
        val libraryId = demoLibraryId()
        if (libraryId == null) {
            logger.info { "seed [$domainName]: no demo library yet — deferring to next restart" }
            return
        }

        val inboxResult = collectionService.getOrCreateInbox(libraryId)
        if (inboxResult is AppResult.Failure) {
            logger.warn { "seed [$domainName]: inbox not created — ${inboxResult.error.code}" }
            return
        }
        logger.info { "seed [$domainName]: inbox ready id=${(inboxResult as AppResult.Success).data.id.value}" }

        seedDemoCollection(libraryId)
    }

    private suspend fun seedDemoCollection(libraryId: String) {
        val ownerId = demoUserId()
        if (ownerId == null) {
            logger.info { "seed [$domainName]: demo user not found — skipping the demo collection" }
            return
        }
        val payload =
            CollectionSyncPayload(
                id = Uuid.random().toString(),
                libraryId = libraryId,
                ownerId = ownerId,
                name = DEMO_COLLECTION_NAME,
                isInbox = false,
                revision = 0L,
                updatedAt = clock.now().toEpochMilliseconds(),
            )
        when (val result = collectionRepo.upsert(payload)) {
            is AppResult.Success -> {
                logger.info {
                    "seed [$domainName]: '$DEMO_COLLECTION_NAME' created id=${result.data.id}"
                }
            }

            is AppResult.Failure -> {
                logger.warn {
                    "seed [$domainName]: '$DEMO_COLLECTION_NAME' not created — ${result.error.code}"
                }
            }
        }
    }

    /** Returns the first non-deleted library's id, or null when none exists yet. */
    private suspend fun demoLibraryId(): String? =
        suspendTransaction(sql) {
            sql.librariesQueries.selectFirstLiveId().executeAsOneOrNull()
        }

    /** Returns the demo user's id string, or null if not yet in the database. */
    private suspend fun demoUserId(): String? =
        suspendTransaction(sql) {
            sql.usersQueries
                .selectByEmailNormalized(email_normalized = UserDomainSeeder.DEMO_EMAIL)
                .executeAsOneOrNull()
                ?.id
        }
}
