package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.server.services.UserStatsRepository

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRule]:
 * a non-allowlisted class that both holds a [UserStatsRepository] dependency and calls `.upsert(`
 * on it. Exists ONLY so
 * [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRuleSelfTest] can assert the
 * rule fires on a real violation. Never imported or instantiated by production code, and excluded
 * from [com.lemonappdev.konsist.api.Konsist.scopeFromProduction] because it lives under `jvmTest`.
 */
internal class RogueStatsWriterFixture(
    private val userStatsRepo: UserStatsRepository,
) {
    suspend fun corruptStats(payload: UserStatsSyncPayload) {
        userStatsRepo.upsert(payload, clientOpId = null, userId = "rogue")
    }
}
