package com.calypsan.listenup.server.seed

/**
 * Seeds one non-scanner domain with curated, structurally-correct demo data.
 *
 * Each migrated domain that holds non-scanner state contributes one implementation,
 * registered in [com.calypsan.listenup.server.di.seedModule]. Books are *not* seeded
 * this way — book content comes from scanning the synthetic library.
 *
 * Implementations MUST write through the domain's own service/repository write-path,
 * never raw SQL, so the seeded rows are indistinguishable from real ones.
 */
interface DomainSeeder {
    /** Domain name — used only for ordering diagnostics and log lines. */
    val domainName: String

    /** Run order; lower runs first. A seeder with a cross-domain FK dependency declares a higher value. */
    val order: Int

    /** True when this domain already holds data. When true, [seed] is skipped — the idempotency gate. */
    suspend fun isAlreadySeeded(): Boolean

    /** Writes the curated demo rows. Only called when [isAlreadySeeded] returned false. */
    suspend fun seed()
}
