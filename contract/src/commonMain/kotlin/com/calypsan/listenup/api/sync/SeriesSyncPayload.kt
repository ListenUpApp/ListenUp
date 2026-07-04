package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a single book series as a first-class syncable domain.
 *
 * B1 carries only identity and display fields — `id`, `name`, `sortName` — plus
 * the substrate bookkeeping columns.
 *
 * B2a adds enrichment fields (`asin`, `description`, `coverPath`, `coverBlurHash`).
 * All are nullable with `null` defaults so existing fixtures and B1-era sync events
 * remain forward-compatible; a receiver that omits them simply keeps whatever
 * enrichment it already had.
 *
 * Implements [Tombstoned] so the substrate's soft-delete routing applies
 * uniformly.
 */
@Serializable
@SerialName("SeriesSyncPayload")
data class SeriesSyncPayload(
    override val id: String,
    val name: String,
    val sortName: String?,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
    // B2a enrichment — null means "not enriched yet"; clients preserve existing value on null.
    val asin: String? = null,
    val description: String? = null,
    val coverPath: String? = null,
    val coverBlurHash: String? = null,
) : SyncPayload
