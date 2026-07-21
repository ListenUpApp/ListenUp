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
 * Firehose `Deleted` is declared [DeleteSemantics.CatchUpOnly]: the server never
 * tombstones stats (the row lives for the user's lifetime); a defensive frame
 * converges via catch-up. Full digest participation.
 *
 * **Intentionally client-dormant (no production reader):** the mirror is synced into Room but no
 * surface reads the `user_stats` table today — Home derives its stats locally from `listening_events`
 * (for instant offline reactivity), and the leaderboard/profile read `public_profiles`. Kept as the
 * natural home for the owner's canonical server stats (a future rich-stats screen would read it): a
 * deliberately-maintained-but-unread mirror, not dead code.
 */
internal fun userStatsDomain(database: ListenUpDatabase): MirroredDomain<UserStatsSyncPayload> =
    MirroredDomain(
        key = SyncDomains.USER_STATS,
        apply = UserStatsMirrorApply(database),
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.userStatsDao().revisionOf(id) }),
        deletes =
            DeleteSemantics.CatchUpOnly(
                "server never tombstones user_stats; the row lives for the user's lifetime",
            ),
        digest = fullDigest(database.userStatsDao()::digestRows),
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

    override suspend fun tombstoneFromItem(item: UserStatsSyncPayload) {
        database.userStatsDao().softDelete(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
