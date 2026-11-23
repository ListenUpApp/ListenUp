package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRule]'s raw-table
 * census: writes `book_reads` straight through SQLDelight, once directly and once through a local alias,
 * holding no stats repository at all. Exists ONLY so
 * [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRuleSelfTest] can assert the census
 * sees both spellings. Never called by production code, and outside
 * [com.lemonappdev.konsist.api.Konsist.scopeFromProduction] because it lives under `jvmTest`.
 */
internal class RogueStatsTableWriterFixture(
    private val sql: ListenUpDatabase,
) {
    fun wipeReads(userId: String) {
        sql.bookReadsQueries.deletePulledForUser(userId)
        val reads = sql.bookReadsQueries
        reads.updateFinishedAtById(finished_at = 0L, id = "rogue")
        // A read is not a write: neither this nor the comment's `bookReadsQueries.insert(` counts.
        reads.countForUser(userId)
    }
}
