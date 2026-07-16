package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Public_profiles
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for the global `public_profiles` projection.
 *
 * Global (`userScoped` defaults `false`) — every client receives every user's row, so
 * pull/digest go through the unfiltered substrate variants (the [TagRepository] pattern).
 * Single-table; the maintainer assembles the full payload, so [writePayload] is a straight
 * INSERT/UPDATE of all columns.
 *
 * The base [SqlSyncableRepository] owns the revision-bump / timestamp / created-vs-updated /
 * emit-after-commit orchestration; this class supplies only the profile-shaped pieces:
 *  - [substrate] — the [SyncableSubstrateQueries] adapter over `publicProfilesQueries`
 *  - [readPayload] / [readPayloads] — root-row reads by id
 *  - [writePayload] — insert-or-update inside the open transaction
 *  - `PublicProfileSyncPayload.id`
 *
 * Service-layer helper beyond the base substrate:
 *  - [identities] — batched (id, displayName, avatarType) read for the social presence surfaces.
 *
 * `id` is a plain `String` (`id == userId`), so the default `idAsString` is correct.
 */
class PublicProfileRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<PublicProfileSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.PUBLIC_PROFILES,
        clock = clock,
    ) {
    override val PublicProfileSyncPayload.id: String get() = this.id

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.publicProfilesQueries].
     * Global aggregate — only the four unfiltered substrate methods are wired; the `*ForUser`
     * variants keep the throwing defaults (never called for a non-userScoped domain).
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.publicProfilesQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.publicProfilesQueries
                    .softDeleteById(
                        revision = revision,
                        updated_at = updatedAt,
                        deleted_at = deletedAt,
                        client_op_id = clientOpId,
                        id = id,
                    ).value

            override fun selectIdsAboveRevision(
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.publicProfilesQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.publicProfilesQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): PublicProfileSyncPayload? =
        db.publicProfilesQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<PublicProfileSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.publicProfilesQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: PublicProfileSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.publicProfilesQueries.update(
                display_name = value.displayName,
                avatar_type = value.avatarType,
                tagline = value.tagline,
                total_seconds_all_time = value.totalSecondsAllTime,
                total_seconds_last_7_days = value.totalSecondsLast7Days,
                total_seconds_last_30_days = value.totalSecondsLast30Days,
                total_seconds_last_365_days = value.totalSecondsLast365Days,
                books_finished = value.booksFinished.toLong(),
                current_streak_days = value.currentStreakDays.toLong(),
                longest_streak_days = value.longestStreakDays.toLong(),
                books_finished_last_7_days = value.booksFinishedLast7Days.toLong(),
                books_finished_last_30_days = value.booksFinishedLast30Days.toLong(),
                books_finished_last_365_days = value.booksFinishedLast365Days.toLong(),
                longest_streak_last_7_days = value.longestStreakLast7Days.toLong(),
                longest_streak_last_30_days = value.longestStreakLast30Days.toLong(),
                longest_streak_last_365_days = value.longestStreakLast365Days.toLong(),
                avatar_updated_at = value.avatarUpdatedAt,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.publicProfilesQueries.insert(
                id = value.id,
                display_name = value.displayName,
                avatar_type = value.avatarType,
                tagline = value.tagline,
                total_seconds_all_time = value.totalSecondsAllTime,
                total_seconds_last_7_days = value.totalSecondsLast7Days,
                total_seconds_last_30_days = value.totalSecondsLast30Days,
                total_seconds_last_365_days = value.totalSecondsLast365Days,
                books_finished = value.booksFinished.toLong(),
                current_streak_days = value.currentStreakDays.toLong(),
                longest_streak_days = value.longestStreakDays.toLong(),
                books_finished_last_7_days = value.booksFinishedLast7Days.toLong(),
                books_finished_last_30_days = value.booksFinishedLast30Days.toLong(),
                books_finished_last_365_days = value.booksFinishedLast365Days.toLong(),
                longest_streak_last_7_days = value.longestStreakLast7Days.toLong(),
                longest_streak_last_30_days = value.longestStreakLast30Days.toLong(),
                longest_streak_last_365_days = value.longestStreakLast365Days.toLong(),
                avatar_updated_at = value.avatarUpdatedAt,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Batch identity read: the public display name and avatar type for each live profile in
     * [ids]. Tombstoned (`deleted_at` set) and absent rows are simply missing from the result —
     * callers drop sessions whose user has no identity to display. An empty [ids] short-circuits
     * without a query.
     */
    suspend fun identities(ids: Set<String>): Map<String, PublicIdentity> {
        if (ids.isEmpty()) return emptyMap()
        return suspendTransaction(db) {
            ids
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.publicProfilesQueries.identitiesForIds(chunk).executeAsList() }
                .associate { row ->
                    row.id to PublicIdentity(displayName = row.display_name, avatarType = row.avatar_type)
                }
        }
    }

    /** Maps a generated [Public_profiles] row to the wire [PublicProfileSyncPayload] DTO. */
    private fun Public_profiles.toSyncPayload(): PublicProfileSyncPayload =
        PublicProfileSyncPayload(
            id = id,
            displayName = display_name,
            avatarType = avatar_type,
            tagline = tagline,
            totalSecondsAllTime = total_seconds_all_time,
            totalSecondsLast7Days = total_seconds_last_7_days,
            totalSecondsLast30Days = total_seconds_last_30_days,
            totalSecondsLast365Days = total_seconds_last_365_days,
            booksFinished = books_finished.toInt(),
            currentStreakDays = current_streak_days.toInt(),
            longestStreakDays = longest_streak_days.toInt(),
            booksFinishedLast7Days = books_finished_last_7_days.toInt(),
            booksFinishedLast30Days = books_finished_last_30_days.toInt(),
            booksFinishedLast365Days = books_finished_last_365_days.toInt(),
            longestStreakLast7Days = longest_streak_last_7_days.toInt(),
            longestStreakLast30Days = longest_streak_last_30_days.toInt(),
            longestStreakLast365Days = longest_streak_last_365_days.toInt(),
            avatarUpdatedAt = avatar_updated_at,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}

/**
 * A user's display identity projected from `public_profiles`, for the social presence
 * surfaces. [avatarType] is `"auto"` or `"image"`.
 */
data class PublicIdentity(
    val displayName: String,
    val avatarType: String,
)
