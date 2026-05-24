package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Table

/**
 * Server-only TTL cache for external Audible/iTunes metadata responses (Books-B2a).
 *
 * Entries are keyed by [cacheKey] (e.g. `"audible:asin:B00FOO"`). [expiresAt] is
 * an epoch-milliseconds deadline; the scheduled cleanup task
 * (`MetadataCacheCleanupTask`) sweeps expired rows periodically so the table stays
 * bounded. [payloadJson] carries the raw provider response — deserialization
 * happens at read time so the cache is provider-agnostic.
 *
 * This table never crosses the wire — it is purely a server-side concern.
 */
internal object MetadataCacheTable : Table("metadata_cache") {
    val cacheKey = varchar("cache_key", 200)
    val region = varchar("region", 8)
    val payloadJson = text("payload_json")
    val fetchedAt = long("fetched_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(cacheKey)
}
