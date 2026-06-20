package com.calypsan.listenup.server.seed

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.contributorDedupKey
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Adds biography, sort name, and website enrichment to the demo contributors
 * created by the scanner when it processes the synthetic seed library.
 *
 * Demo contributor IDs are not fixed (the scanner generates UUIDs at scan time),
 * so enrichment is keyed by [contributorDedupKey] — the same deduplication key
 * that [ContributorRepository.resolveOrCreate] writes at INSERT time. This means
 * the seeder finds scanner-created rows regardless of whether the scanner supplied
 * an explicit sort name or derived one internally.
 *
 * Idempotency: a contributor whose `description` is already non-blank is
 * skipped. Runs after the initial scanner pass (order 30 — well above the
 * [PlaybackPositionDomainSeeder] at order 10 and [ListeningEventDomainSeeder]
 * at order 20).
 *
 * If the scanner hasn't run yet when this seeder fires, the contributor rows
 * won't exist and the enrichment is silently skipped — the same graceful-defer
 * pattern used by [PlaybackPositionDomainSeeder] and [ListeningEventDomainSeeder].
 */
internal class ContributorEnrichmentSeeder(
    private val sql: ListenUpDatabase,
    private val contributorRepository: ContributorRepository,
) : DomainSeeder {
    override val domainName: String = "contributor_enrichment"

    /**
     * Runs after [ListeningEventDomainSeeder] (order 20). The scanner writes
     * contributor rows; this seeder enriches them, so the scanner must have
     * run first.
     */
    override val order: Int = 30

    override suspend fun isAlreadySeeded(): Boolean =
        suspendTransaction(sql) {
            // Already-seeded if ANY enriched contributor row exists — i.e. at
            // least one row whose description is non-null.
            sql.contributorsQueries.hasAnyEnrichedContributor().executeAsOne()
        }

    override suspend fun seed() {
        ENRICHMENTS.forEach { enrichment ->
            val existing = findByName(enrichment.displayName)
            if (existing == null) {
                logger.info {
                    "seed [$domainName]: '${enrichment.displayName}' not in DB yet — " +
                        "scanner hasn't run; deferring"
                }
                return@forEach
            }
            if (!existing.description.isNullOrBlank()) {
                return@forEach // already enriched — idempotent skip
            }
            contributorRepository.upsert(
                existing.copy(
                    description = enrichment.description,
                    sortName = enrichment.sortName,
                    website = enrichment.website,
                ),
                clientOpId = null,
            )
            logger.info { "seed [$domainName]: enriched '${enrichment.displayName}'" }
        }
    }

    private suspend fun findByName(displayName: String) =
        // contributorDedupKey derives the sort form (e.g. "Wren Halloway" → "halloway, wren"),
        // matching the key the scanner writes at INSERT time via resolveOrCreate.
        suspendTransaction(sql) {
            val key = contributorDedupKey(displayName, null)
            sql.contributorsQueries
                .selectByNormalizedName(normalized_name = key)
                .executeAsOneOrNull()
                ?.id
        }?.let { id -> contributorRepository.findById(id) }

    private companion object {
        val ENRICHMENTS =
            listOf(
                EnrichmentSpec(
                    displayName = "Wren Halloway",
                    sortName = "Halloway, Wren",
                    description =
                        "Wren Halloway is the author of the bestselling Ember Codex trilogy, " +
                            "a sweeping fantasy series that charts one woman's journey from " +
                            "reluctant archivist to keeper of a forgotten flame.",
                    website = null,
                ),
                EnrichmentSpec(
                    displayName = "Marlowe Finch",
                    sortName = "Finch, Marlowe",
                    description =
                        "Marlowe Finch is an Audie Award–nominated narrator celebrated for " +
                            "breathing life into epic fantasy worlds. He has recorded over " +
                            "two hundred audiobooks across a career spanning fifteen years.",
                    website = null,
                ),
                EnrichmentSpec(
                    displayName = "Cassia Vane",
                    sortName = "Vane, Cassia",
                    description =
                        "Cassia Vane writes intricate clockpunk adventures set in archipelagos " +
                            "of brass and steam. Her debut, The Clockwork Archipelago, was " +
                            "nominated for the Nebula Award for Best Novel.",
                    website = null,
                ),
                EnrichmentSpec(
                    displayName = "Theo Morrow",
                    sortName = "Morrow, Theo",
                    description =
                        "Theo Morrow is a cartographer turned fiction writer whose speculative " +
                            "novels explore the edges of known worlds — both literal and emotional.",
                    website = null,
                ),
                EnrichmentSpec(
                    displayName = "Aldric Penn",
                    sortName = "Penn, Aldric",
                    description =
                        "Aldric Penn is the author of stark, spare fiction set along forgotten " +
                            "trade routes. His prose has been compared to Cormac McCarthy for " +
                            "its economy and moral weight.",
                    website = null,
                ),
                EnrichmentSpec(
                    displayName = "Petra Lund",
                    sortName = "Lund, Petra",
                    description =
                        "Petra Lund writes quiet, luminous literary fiction from her home in " +
                            "Reykjavik. The Hollow Winter, her most celebrated novel, follows " +
                            "a lighthouse keeper through eleven months of solitude.",
                    website = null,
                ),
            )
    }
}

/** One enrichment record keyed by the contributor's display name. */
private data class EnrichmentSpec(
    val displayName: String,
    val sortName: String?,
    val description: String,
    val website: String?,
)
