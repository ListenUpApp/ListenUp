package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.ContributorAliasCrossRef
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
 * does not carry uniformly. The wire is authoritative only for identity; every
 * enrichment column is copied from the existing row when the payload's value is
 * null so a B1-era sync never nulls Books-B2 data.
 *
 * **Aliases (Books-C2).** [ContributorSyncPayload.aliases] is the server-side
 * authority for the contributor's pen names (populated via merge/unmerge). The
 * handler mirrors the wire list into the `contributor_aliases` junction with a
 * `delete-then-insert` replace inside the surrounding transaction. Soft-delete
 * tombstones also clear the junction — the contributor row stays (so revision
 * bookkeeping survives) but its display aliases do not outlive its identity.
 *
 * `isOwnEcho` is ignored: clients have no direct alias-write path; the only way
 * aliases mutate is through server merge/unmerge, which the server-side
 * authority pushes back via this handler.
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
                    tombstone(
                        id = event.id,
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
                tombstone(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    /**
     * Apply a server tombstone: soft-delete the contributor row and clear its
     * aliases. The row stays (soft delete is a column update, not a row delete,
     * so the FK cascade on `contributor_aliases` does not fire); the aliases
     * junction is cleared explicitly because aliases are display data tied to a
     * live identity.
     */
    private suspend fun tombstone(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.contributorDao().softDelete(
            id = ContributorId(id),
            deletedAt = deletedAt,
            revision = revision,
        )
        database.contributorAliasDao().deleteForContributor(id)
    }

    /**
     * Upsert the contributor row and mirror its aliases.
     *
     * Enrichment fields are applied from the wire payload when non-null; when null
     * (a B1-era event that predates enrichment), the existing row's value is
     * preserved so a B1 sync never zeroes Books-B2 data.
     *
     * Aliases are server-authoritative — the wire list replaces whatever is in
     * the junction. The replace is `delete + insert` inside the surrounding
     * transaction; the DAO's `OnConflictStrategy.IGNORE` handles any exact-case
     * duplicates the server happens to emit.
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
        replaceAliases(payload.id, payload.aliases)
    }

    /**
     * Replace the contributor's alias junction rows with [aliases]. Composes
     * `deleteForContributor` + `insertAll` inside the surrounding transaction
     * per the DAO contract.
     */
    private suspend fun replaceAliases(
        contributorId: String,
        aliases: List<String>,
    ) {
        val aliasDao = database.contributorAliasDao()
        aliasDao.deleteForContributor(contributorId)
        if (aliases.isNotEmpty()) {
            aliasDao.insertAll(
                aliases.map { ContributorAliasCrossRef(ContributorId(contributorId), it) },
            )
        }
    }
}
