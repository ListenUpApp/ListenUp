package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.server.services.BookReadsRepository

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRule]:
 * a non-allowlisted class that both holds a [BookReadsRepository] dependency and calls
 * `.recordRead(` on it — the `BookReadsRepository`/`.recordRead(` half of the rule, distinct from
 * [RogueStatsWriterFixture]'s `UserStatsRepository`/`.upsert(` half. Exists ONLY so
 * [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRuleSelfTest] can assert that
 * matcher independently fires — without this fixture, a typo in the `.recordRead(` token would
 * leave both the production scan and the self-test green. Never imported or instantiated by
 * production code, and excluded from [com.lemonappdev.konsist.api.Konsist.scopeFromProduction]
 * because it lives under `jvmTest`.
 */
internal class RogueBookReadsWriterFixture(
    private val bookReadsRepo: BookReadsRepository,
) {
    suspend fun corruptReads() {
        bookReadsRepo.recordRead(
            id = "rogue",
            userId = "rogue",
            bookId = "rogue",
            finishedAt = 0L,
            source = "rogue",
        )
    }
}
