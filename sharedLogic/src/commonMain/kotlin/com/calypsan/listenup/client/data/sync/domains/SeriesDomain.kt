package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp

/**
 * The `series` domain (Books-B1): server-wins apply with enrichment copy-forward,
 * soft-delete tombstones, full digest, outbox updates (#977 offline edits).
 *
 * **Enrichment preservation.** `description`, `asin`, and cover imagery are copied
 * from the existing row when the payload's value is null — a B1-era event must
 * never null Books-B2 enrichment. This is apply-layer mapping, not conflict policy.
 * An own-echo needs no shield: idempotent upsert; edits echo identical values.
 */
internal fun seriesDomain(database: ListenUpDatabase): MirroredDomain<SeriesSyncPayload> {
    val apply = SeriesMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.SERIES,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.seriesDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.seriesDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.Series),
    )
}

/** Room mapping for [SeriesSyncPayload] payloads (enrichment copy-forward). */
internal class SeriesMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<SeriesSyncPayload> {
    override suspend fun upsert(payload: SeriesSyncPayload) {
        val existing = database.seriesDao().getById(payload.id)
        database.seriesDao().upsert(
            SeriesEntity(
                id = SeriesId(payload.id),
                name = payload.name,
                sortName = payload.sortName,
                description = payload.description ?: existing?.description,
                asin = payload.asin ?: existing?.asin,
                coverPath = payload.coverPath ?: existing?.coverPath,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                createdAt = Timestamp(payload.createdAt),
                updatedAt = Timestamp(payload.updatedAt),
            ),
        )
    }

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.seriesDao().softDelete(
            id = SeriesId(id),
            deletedAt = deletedAt,
            revision = revision,
        )
    }

    override suspend fun tombstoneFromItem(item: SeriesSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
