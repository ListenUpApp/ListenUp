package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.MetadataCacheTable
import com.calypsan.listenup.api.metadata.AudibleRegion
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

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
 * [MetadataCacheTable] uses `cache_key` as the sole primary key. To allow the
 * same logical cache key (e.g. `"search:harry potter"`) to store independent
 * entries per [AudibleRegion], this repository prepends the region code to form
 * the stored key: `"${region.code}:${cacheKey}"`. The [MetadataCacheTable.region]
 * column stores the plain region code for diagnostics and potential future
 * region-filtered sweeps.
 */
internal class MetadataCacheRepository(
    private val db: Database,
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
                MetadataCacheTable
                    .selectAll()
                    .where { MetadataCacheTable.cacheKey eq storedKey }
                    .firstOrNull()
                    ?: return@suspendTransaction null

            val nowMs = clock.now().toEpochMilliseconds()
            if (row[MetadataCacheTable.expiresAt] <= nowMs) {
                MetadataCacheTable.deleteWhere { MetadataCacheTable.cacheKey eq storedKey }
                null
            } else {
                row[MetadataCacheTable.payloadJson]
            }
        }

    /**
     * Store or replace [payloadJson] for [cacheKey] in [region], expiring at
     * the absolute epoch-millisecond timestamp [expiresAt].
     *
     * If a row already exists for the same key+region, it is updated in-place
     * (upsert semantics). A separate [get] call after [put] always sees the new
     * value, even within the same coroutine.
     */
    suspend fun put(
        cacheKey: String,
        region: AudibleRegion,
        payloadJson: String,
        expiresAt: Long,
    ) = suspendTransaction(db) {
        val storedKey = storedKey(cacheKey, region)
        val nowMs = clock.now().toEpochMilliseconds()

        val existing =
            MetadataCacheTable
                .selectAll()
                .where { MetadataCacheTable.cacheKey eq storedKey }
                .firstOrNull()

        if (existing != null) {
            MetadataCacheTable.update({ MetadataCacheTable.cacheKey eq storedKey }) {
                it[MetadataCacheTable.payloadJson] = payloadJson
                it[MetadataCacheTable.fetchedAt] = nowMs
                it[MetadataCacheTable.expiresAt] = expiresAt
            }
        } else {
            MetadataCacheTable.insert {
                it[MetadataCacheTable.cacheKey] = storedKey
                it[MetadataCacheTable.region] = region.code
                it[MetadataCacheTable.payloadJson] = payloadJson
                it[MetadataCacheTable.fetchedAt] = nowMs
                it[MetadataCacheTable.expiresAt] = expiresAt
            }
        }
    }

    /**
     * Delete all rows whose [MetadataCacheTable.expiresAt] is strictly less
     * than [beforeMs]. Returns the count of deleted rows.
     *
     * Called by the periodic cleanup task. Using strict `<` (not `<=`) ensures
     * a row expiring at exactly [beforeMs] survives until the next sweep —
     * consistent with [get]'s `<= nowMs` expiry check.
     */
    suspend fun deleteExpired(beforeMs: Long): Int =
        suspendTransaction(db) {
            MetadataCacheTable.deleteWhere { MetadataCacheTable.expiresAt less beforeMs }
        }

    /**
     * Derives the stored primary key from the caller's logical [cacheKey] and
     * [region]. Prepending the region code keeps entries for different regions
     * distinct even when the logical key is identical, since
     * [MetadataCacheTable.cacheKey] is the sole primary key.
     */
    private fun storedKey(
        cacheKey: String,
        region: AudibleRegion,
    ): String = "${region.code}:$cacheKey"
}
