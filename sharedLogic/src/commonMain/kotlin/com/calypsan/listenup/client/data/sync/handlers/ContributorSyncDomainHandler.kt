package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `contributors` domain (Books-B1).
 *
 * Applies server sync events into the Room `contributors` table. The handler is
 * the authority for a contributor's identity fields (`name`, `sortName`); the
 * book sync handler only ever bootstraps a *missing* contributor stub and never
 * overwrites a row this handler owns.
 *
 * **Enrichment preservation.** A contributor row carries enrichment columns
 * (`description`, `imagePath`, `birthDate`, …) that [ContributorSyncPayload]
 * does not. The wire is authoritative only for identity; every enrichment
 * column is copied from the existing row so a B1 sync never nulls Books-B2 data.
 *
 * `isOwnEcho` is ignored: B1 has no client-side contributor write path, so an
 * echo can never occur. Books-C revisits this when contributor edits land.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class ContributorSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<ContributorSyncPayload> {
    override val domainName: String = "contributors"
    override val payloadSerializer = ContributorSyncPayload.serializer()

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<ContributorSyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            when (event) {
                is SyncEvent.Created -> {
                    upsert(event.payload)
                }

                is SyncEvent.Updated -> {
                    upsert(event.payload)
                }

                is SyncEvent.Deleted -> {
                    database.contributorDao().softDelete(
                        id = ContributorId(event.id),
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: ContributorSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.contributorDao().softDelete(
                    id = ContributorId(item.id),
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    /**
     * Upsert the contributor row.
     *
     * Enrichment fields are applied from the wire payload when non-null; when null
     * (a B1-era event that predates enrichment), the existing row's value is
     * preserved so a B1 sync never zeroes Books-B2 data.
     */
    private suspend fun upsert(payload: ContributorSyncPayload) {
        val existing = database.contributorDao().getById(payload.id)
        database.contributorDao().upsert(
            ContributorEntity(
                id = ContributorId(payload.id),
                name = payload.name,
                sortName = payload.sortName,
                asin = payload.asin ?: existing?.asin,
                description = payload.description ?: existing?.description,
                imagePath = payload.imagePath ?: existing?.imagePath,
                imageBlurHash = payload.imageBlurHash ?: existing?.imageBlurHash,
                website = payload.website ?: existing?.website,
                birthDate = payload.birthDate ?: existing?.birthDate,
                deathDate = payload.deathDate ?: existing?.deathDate,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                createdAt = Timestamp(payload.createdAt),
                updatedAt = Timestamp(payload.updatedAt),
            ),
        )
    }
}
