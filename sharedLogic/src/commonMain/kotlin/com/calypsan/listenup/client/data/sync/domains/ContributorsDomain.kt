package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ContributorAliasCrossRef
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp

/**
 * The `contributors` domain (Books-B1): server-wins apply with enrichment
 * copy-forward and alias mirroring, soft-delete tombstones (which also clear the
 * alias junction), full digest, outbox updates (#977 offline edits).
 *
 * **Enrichment preservation.** Enrichment columns (`description`, `imagePath`,
 * `birthDate`, …) fall back to the existing row when the payload's value is null —
 * a B1-era event must never null Books-B2 data.
 *
 * **Aliases (Books-C2).** The wire list is server-authoritative (merge/unmerge);
 * mirrored with delete-then-insert replace inside the surrounding transaction.
 *
 * `isOwnEcho` needs no shield: clients have no direct alias-write path.
 */
internal fun contributorsDomain(
    database: ListenUpDatabase,
    imageStorage: ImageStorage,
): MirroredDomain<ContributorSyncPayload> {
    val apply = ContributorMirrorApply(database, imageStorage)
    return MirroredDomain(
        key = SyncDomains.CONTRIBUTORS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.contributorDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.contributorDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.Contributors),
    )
}

/**
 * Room mapping for [ContributorSyncPayload]: enrichment copy-forward, alias junction
 * replace, and content-addressed image cleanup on `imagePath` change.
 */
internal class ContributorMirrorApply(
    private val database: ListenUpDatabase,
    private val imageStorage: ImageStorage,
) : MirrorApply<ContributorSyncPayload> {
    override suspend fun upsert(payload: ContributorSyncPayload) {
        val existing = database.contributorDao().getById(payload.id)
        val newImagePath = payload.imagePath ?: existing?.imagePath
        // The server stores contributor photos content-addressed, so a re-scrape changes imagePath.
        // The local copy is otherwise never re-downloaded (the downloader skips when a file exists),
        // so drop it on change: the render then re-fetches the new photo. Best-effort.
        if (existing != null && existing.imagePath != newImagePath) {
            val _ = imageStorage.deleteContributorImage(payload.id)
        }
        database.contributorDao().upsert(
            ContributorEntity(
                id = ContributorId(payload.id),
                name = payload.name,
                sortName = payload.sortName,
                asin = payload.asin ?: existing?.asin,
                description = payload.description ?: existing?.description,
                imagePath = newImagePath,
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
     * Soft-delete the contributor row and clear its aliases. The row stays (soft
     * delete is a column update, so the FK cascade on `contributor_aliases` does not
     * fire); the junction is cleared explicitly because aliases are display data
     * tied to a live identity.
     */
    suspend fun tombstoneById(
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

    override suspend fun tombstoneFromItem(item: ContributorSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }

    /**
     * Replace the contributor's alias junction rows with [aliases]. Composes
     * `deleteForContributor` + `insertAll` inside the surrounding transaction per
     * the DAO contract; `OnConflictStrategy.IGNORE` absorbs exact-case duplicates.
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
