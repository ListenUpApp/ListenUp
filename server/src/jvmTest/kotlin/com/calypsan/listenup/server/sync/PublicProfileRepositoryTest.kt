package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private fun row(
    id: String,
    secs: Long,
) = PublicProfileSyncPayload(
    id = id,
    displayName = "User $id",
    avatarType = "auto",
    tagline = null,
    totalSecondsAllTime = secs,
    totalSecondsLast7Days = secs,
    totalSecondsLast30Days = secs,
    totalSecondsLast365Days = secs,
    booksFinished = 1,
    currentStreakDays = 1,
    longestStreakDays = 1,
    revision = 0,
    updatedAt = 0,
    createdAt = 0,
    deletedAt = null,
)

class PublicProfileRepositoryTest :
    FunSpec({
        test("pullSince returns ALL users' rows regardless of requesting user (global domain)") {
            withSqlDatabase {
                val repo = PublicProfileRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(row("u1", 100))
                    repo.upsert(row("u2", 200))

                    // userId is irrelevant for a global domain — both rows come back either way.
                    val page = repo.pullSince(userId = "u1", cursor = 0, limit = 50)
                    page.items.map { item -> item.id }.sorted() shouldBe listOf("u1", "u2")
                    page.items shouldHaveSize 2
                }
            }
        }
    })
