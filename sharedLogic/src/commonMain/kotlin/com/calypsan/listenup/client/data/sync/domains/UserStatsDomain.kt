package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.UserStatsEntity

/**
 * The `user_stats` domain: a server-maintained materialized view — the server
 * computes everything from the canonical event log and is the sole writer, so the
 * client replaces its row unconditionally (server-wins, no local-only columns).
 * [WriteTier.ServerOwned]: no client-originated writes exist.
 *
 * SSE `Deleted` is declared [DeleteSemantics.CatchUpOnly]: the server never
 * tombstones stats (the row lives for the user's lifetime); a defensive frame
 * converges via catch-up. Full digest participation.
 */
internal fun userStatsDomain(database: ListenUpDatabase): MirroredDomain<UserStatsSyncPayload> =
    MirroredDomain(
        key = SyncDomains.USER_STATS,
        syncIdOf = { it.id },
        apply = UserStatsMirrorApply(database),
        conflict = ConflictPolicy.ServerWins(),
        deletes =
            DeleteSemantics.CatchUpOnly(
                "server never tombstones user_stats; the row lives for the user's lifetime",
            ),
        digest =
            DigestParticipation.Full { maxRevision ->
                database.userStatsDao().digestRows(maxRevision).map { it.id to it.revision }
            },
        writes = WriteTier.ServerOwned,
    )

/** Room mapping for [UserStatsSyncPayload]: unconditional replace, no merge logic. */
internal class UserStatsMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<UserStatsSyncPayload> {
    override suspend fun upsert(payload: UserStatsSyncPayload) {
        database.userStatsDao().upsert(
            UserStatsEntity(
                id = payload.id,
                totalSecondsAllTime = payload.totalSecondsAllTime,
                totalSecondsLast7Days = payload.totalSecondsLast7Days,
                totalSecondsLast30Days = payload.totalSecondsLast30Days,
                booksStarted = payload.booksStarted,
                booksFinished = payload.booksFinished,
                currentStreakDays = payload.currentStreakDays,
                longestStreakDays = payload.longestStreakDays,
                lastEventDate = payload.lastEventDate,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    override suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ): Unit = error("unreachable: user_stats declares DeleteSemantics.CatchUpOnly")

    override suspend fun tombstoneFromItem(item: UserStatsSyncPayload) {
        database.userStatsDao().softDelete(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
