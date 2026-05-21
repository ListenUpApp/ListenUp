package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a single book series as a first-class syncable domain.
 *
 * B1 carries only identity and display fields — `id`, `name`, `sortName` — plus
 * the substrate bookkeeping columns. Enrichment fields (`description`, `asin`,
 * cover imagery) are deliberately absent: B1 has no path to populate them.
 * Books-B2's match service extends this payload when it has enrichment data.
 *
 * Implements [Tombstoned] so the substrate's soft-delete routing applies
 * uniformly.
 */
@Serializable
@SerialName("SeriesSyncPayload")
data class SeriesSyncPayload(
    val id: String,
    val name: String,
    val sortName: String?,
    val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : Tombstoned
