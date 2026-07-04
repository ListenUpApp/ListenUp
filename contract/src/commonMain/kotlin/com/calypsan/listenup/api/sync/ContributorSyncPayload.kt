package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a single contributor (author, narrator, translator, …) as a
 * first-class syncable domain.
 *
 * B1 carries only identity and display fields — `id`, `name`, `sortName` — plus
 * the substrate bookkeeping columns.
 *
 * B2a adds enrichment fields (`asin`, `description`, `imagePath`, `imageBlurHash`,
 * `birthDate`, `deathDate`, `website`). All are nullable with `null` defaults so
 * existing fixtures and B1-era sync events remain forward-compatible; a receiver
 * that omits them simply keeps whatever enrichment it already had.
 *
 * Implements [Tombstoned] so the substrate's soft-delete routing applies
 * uniformly. `@SerialName` is the wire-stable discriminator; field renames break
 * wire compatibility, additions are forward-compatible.
 */
@Serializable
@SerialName("ContributorSyncPayload")
data class ContributorSyncPayload(
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
    val imagePath: String? = null,
    val imageBlurHash: String? = null,
    val birthDate: String? = null,
    val deathDate: String? = null,
    val website: String? = null,
    /**
     * Alternative display names for this contributor (AKAs / pen names).
     *
     * Aliases are populated server-side via [com.calypsan.listenup.api.ContributorService.mergeContributors]
     * — when contributor X is merged into Y, X's canonical name becomes one of Y's aliases.
     * [com.calypsan.listenup.api.ContributorService.unmergeContributor] removes one.
     *
     * Defaults to `emptyList()` so payloads serialized before Books-C2 decode cleanly.
     */
    @SerialName("aliases")
    val aliases: List<String> = emptyList(),
) : SyncPayload
