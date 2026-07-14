package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import kotlin.time.Clock

/**
 * Server-only TTL cache for external metadata-provider API responses (Audible,
 * Audnexus, …).
 *
 * This repository is never exposed over the wire — it is purely a server-side
 * performance layer that avoids hammering external APIs on every enrichment
 * request. Eviction is bi-modal:
 * 1. **Lazy eviction**: [get] deletes a row when it reads it and finds it expired.
 * 2. **Sweep eviction**: The scheduled `MetadataCacheCleanupTask` calls
 *    [deleteExpired] periodically so orphaned rows (from keys that are never
 *    re-read) don't accumulate indefinitely.
 *
 * **Provider + region scoping.**
 * The `metadata_cache` table uses `cache_key` as the sole primary key. Different
 * providers and regions can hold the same logical key (e.g. `"book:B0015T963C"`)
 * for wholly different payload shapes, so this repository prepends both to form the
 * stored key: `"${provider}:${region}:${cacheKey}"`. Without the provider prefix an
 * Audible book and an Audnexus book would collide on `book:{asin}` and thrash each
 * other on every read. (Old two-segment `"${region}:${key}"` entries simply age out
 * via TTL.) The `region` column stores the plain region code for diagnostics.
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
     * Return the cached payload JSON for [cacheKey] from [provider] in [region], or
     * `null` if there is no entry or the entry has expired.
     *
     * An expired row is deleted as a side-effect (lazy eviction). The periodic
     * cleanup task handles rows that are never re-read.
     */
    suspend fun get(
        provider: MetadataProviderId,
        region: String,
        cacheKey: String,
    ): String? =
        suspendTransaction(db) {
            val storedKey = storedKey(provider, region, cacheKey)
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
     * Store or replace [payloadJson] for [cacheKey] from [provider] in [region],
     * expiring at the absolute epoch-millisecond timestamp [expiresAt].
     *
     * If a row already exists for the same provider+region+key, it is replaced
     * in-place (upsert semantics, via `INSERT OR REPLACE` on the `cache_key` primary
     * key). A separate [get] call after [put] always sees the new value, even within
     * the same coroutine.
     */
    suspend fun put(
        provider: MetadataProviderId,
        region: String,
        cacheKey: String,
        payloadJson: String,
        expiresAt: Long,
    ) = suspendTransaction(db) {
        db.metadataCacheQueries.upsert(
            cache_key = storedKey(provider, region, cacheKey),
            region = region,
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
     * Derives the stored primary key from the [provider], [region], and caller's
     * logical [cacheKey]. Prepending provider + region keeps entries distinct across
     * providers and regions even when the logical key is identical, since `cache_key`
     * is the sole primary key.
     */
    private fun storedKey(
        provider: MetadataProviderId,
        region: String,
        cacheKey: String,
    ): String = "${provider.value}:$region:$cacheKey"
}
