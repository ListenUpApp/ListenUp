package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository

/**
 * The global `public_profiles` domain: a server-maintained materialized view of a
 * user's public social identity — server-wins replace, no client-writable fields,
 * [WriteTier.ServerOwned]. Unlike [userStatsDomain] this domain tombstones
 * (deleted users): SSE `Deleted` soft-deletes immediately. Full digest.
 *
 * **Avatar force-refresh (bespoke apply side effect).** Avatar bytes change behind
 * a stable path — `avatarUpdatedAt` is the only signal. When it advances for an
 * image avatar, the apply queues a force re-download (fire-and-forget).
 */
internal fun publicProfilesDomain(
    database: ListenUpDatabase,
    avatarDownloadRepository: AvatarDownloadRepository,
): MirroredDomain<PublicProfileSyncPayload> {
    val apply = PublicProfileMirrorApply(database, avatarDownloadRepository)
    return MirroredDomain(
        key = SyncDomains.PUBLIC_PROFILES,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.publicProfileDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.publicProfileDao()::digestRows),
        writes = WriteTier.ServerOwned,
    )
}

/**
 * Room mapping for [PublicProfileSyncPayload]: unconditional replace plus the
 * avatar force-refresh trigger described on [publicProfilesDomain].
 */
internal class PublicProfileMirrorApply(
    private val database: ListenUpDatabase,
    private val avatarDownloadRepository: AvatarDownloadRepository,
) : MirrorApply<PublicProfileSyncPayload> {
    override suspend fun upsert(payload: PublicProfileSyncPayload) {
        val previousAvatarUpdatedAt =
            database.publicProfileDao().findById(payload.id)?.avatarUpdatedAt ?: 0L
        database.publicProfileDao().upsert(
            PublicProfileEntity(
                id = payload.id,
                displayName = payload.displayName,
                avatarType = payload.avatarType,
                tagline = payload.tagline,
                totalSecondsAllTime = payload.totalSecondsAllTime,
                totalSecondsLast7Days = payload.totalSecondsLast7Days,
                totalSecondsLast30Days = payload.totalSecondsLast30Days,
                totalSecondsLast365Days = payload.totalSecondsLast365Days,
                booksFinished = payload.booksFinished,
                currentStreakDays = payload.currentStreakDays,
                longestStreakDays = payload.longestStreakDays,
                booksFinishedLast7Days = payload.booksFinishedLast7Days,
                booksFinishedLast30Days = payload.booksFinishedLast30Days,
                booksFinishedLast365Days = payload.booksFinishedLast365Days,
                longestStreakLast7Days = payload.longestStreakLast7Days,
                longestStreakLast30Days = payload.longestStreakLast30Days,
                longestStreakLast365Days = payload.longestStreakLast365Days,
                avatarUpdatedAt = payload.avatarUpdatedAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
        // Avatar bytes change behind a stable path — avatarUpdatedAt is the only signal. Force a
        // re-download when it advances for an image avatar (fire-and-forget; queue returns immediately).
        if (payload.avatarType == "image" && payload.avatarUpdatedAt != previousAvatarUpdatedAt) {
            avatarDownloadRepository.queueAvatarForceRefresh(payload.id)
        }
    }

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.publicProfileDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: PublicProfileSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
