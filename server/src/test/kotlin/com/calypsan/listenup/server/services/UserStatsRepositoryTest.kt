@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.UserStatsId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class UserStatsRepositoryTest :
    FunSpec({

        test("getForUser returns null before any row exists") {
            withInMemoryDatabase {
                val repo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.getForUser("u1").shouldBeNull()
                }
            }
        }

        test("upsert inserts a row and getForUser returns the payload") {
            withInMemoryDatabase {
                val repo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val payload = userStatsPayload(userId = "u1")
                    val result = repo.upsert(payload, clientOpId = null, userId = "u1")
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getForUser("u1").shouldNotBeNull()
                    stored.totalSecondsAllTime shouldBe payload.totalSecondsAllTime
                    stored.booksFinished shouldBe payload.booksFinished
                    stored.currentStreakDays shouldBe payload.currentStreakDays
                    stored.lastEventDate shouldBe payload.lastEventDate
                }
            }
        }

        test("pullSince(userId = u1) returns only u1's stats row") {
            withInMemoryDatabase {
                val repo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(userStatsPayload(userId = "u1"), clientOpId = null, userId = "u1")
                    repo.upsert(userStatsPayload(userId = "u2"), clientOpId = null, userId = "u2")

                    val page = repo.pullSince(userId = "u1", cursor = 0L, limit = 50)
                    page.items.size shouldBe 1
                    page.items.first().id shouldBe "u1"
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withInMemoryDatabase {
                val repo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(UserStatsId("u1")) shouldBe "u1"
            }
        }
    })

private fun userStatsPayload(userId: String): UserStatsSyncPayload =
    UserStatsSyncPayload(
        id = userId,
        totalSecondsAllTime = 3_600L,
        totalSecondsLast7Days = 600L,
        totalSecondsLast30Days = 1_800L,
        booksStarted = 2,
        booksFinished = 1,
        currentStreakDays = 3,
        longestStreakDays = 7,
        lastEventDate = "2026-05-21",
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
