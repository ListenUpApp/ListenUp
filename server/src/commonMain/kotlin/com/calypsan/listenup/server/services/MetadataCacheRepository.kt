package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * Server-only TTL cache for external Audible and iTunes API responses.
 *
 * This repository is never exposed over the wire — it is purely a server-side
 * performance layer that avoids hammering external APIs on every enrichment
 * request. Eviction is bi-modal:
 * 1. **Lazy eviction**: [get] deletes a row when it reads it and finds it expired.
 * 2. **Sweep eviction**: The scheduled `MetadataCacheCleanupTask` calls
 *    [deleteExpired] periodically so orphaned rows (from keys that are never
 *    re-read) don't accumulate indefinitely.
 *
 * **Region scoping.**
 * The `metadata_cache` table uses `cache_key` as the sole primary key. To allow
 * the same logical cache key (e.g. `"search:harry potter"`) to store independent
 * entries per [AudibleRegion], this repository prepends the region code to form
 * the stored key: `"${region.code}:${cacheKey}"`. The `region` column stores the
 * plain region code for diagnostics and potential future region-filtered sweeps.
 *
 * This is a **plain** (non-syncable) key-value repository — there is no revision /
 * soft-delete substrate, so it persists directly over the generated
 * [ListenUpDatabase.metadataCacheQueries] rather than through `SqlSyncableRepository`.
 */
internal class MetadataCacheRepository(
    private val db: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    /**
     * Return the cached payload JSON for [cacheKey] in [region], or `null` if
     * there is no entry or the entry has expired.
     *
     * An expired row is deleted as a side-effect (lazy eviction). The periodic
     * cleanup task handles rows that are never re-read.
     */
    suspend fun get(
        cacheKey: String,
        region: AudibleRegion,
    ): String? =
        suspendTransaction(db) {
            val storedKey = storedKey(cacheKey, region)
            val row =
                db.metadataCacheQueries
                    .selectByKey(storedKey)
                    .executeAsOneOrNull()
                    ?: return@suspendTransaction null

            val nowMs = clock.now().toEpochMilliseconds()
            if (row.expires_at <= nowMs) {
                db.metadataCacheQueries.deleteByKey(storedKey)
                null
            } else {
                row.payload_json
            }
        }

    /**
     * Store or replace [payloadJson] for [cacheKey] in [region], expiring at
     * the absolute epoch-millisecond timestamp [expiresAt].
     *
     * If a row already exists for the same key+region, it is replaced in-place
     * (upsert semantics, via `INSERT OR REPLACE` on the `cache_key` primary key).
     * A separate [get] call after [put] always sees the new value, even within the
     * same coroutine.
     */
    suspend fun put(
        cacheKey: String,
        region: AudibleRegion,
        payloadJson: String,
        expiresAt: Long,
    ) = suspendTransaction(db) {
        db.metadataCacheQueries.upsert(
            cache_key = storedKey(cacheKey, region),
            region = region.code,
            payload_json = payloadJson,
            fetched_at = clock.now().toEpochMilliseconds(),
            expires_at = expiresAt,
        )
    }

    /**
     * Delete all rows whose `expires_at` is strictly less than [beforeMs]. Returns
     * the count of deleted rows.
     *
     * Called by the periodic cleanup task. Using strict `<` (not `<=`) ensures a
     * row expiring at exactly [beforeMs] survives until the next sweep — consistent
     * with [get]'s `<= nowMs` expiry check.
     */
    suspend fun deleteExpired(beforeMs: Long): Int =
        suspendTransaction(db) {
            db.metadataCacheQueries.deleteExpired(beforeMs)
            db.metadataCacheQueries
                .changes()
                .executeAsOne()
                .toInt()
        }

    /**
     * Derives the stored primary key from the caller's logical [cacheKey] and
     * [region]. Prepending the region code keeps entries for different regions
     * distinct even when the logical key is identical, since `cache_key` is the
     * sole primary key.
     */
    private fun storedKey(
        cacheKey: String,
        region: AudibleRegion,
    ): String = "${region.code}:$cacheKey"
}
