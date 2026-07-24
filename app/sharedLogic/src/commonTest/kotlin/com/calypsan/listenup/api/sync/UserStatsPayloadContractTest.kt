package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserStatsPayloadContractTest :
    FunSpec({

        test("UserStatsSyncPayload round-trips for a fresh user (all zeros, lastEventDate = null)") {
            val original =
                UserStatsSyncPayload(
                    id = "user-1",
                    totalSecondsAllTime = 0L,
                    totalSecondsLast7Days = 0L,
                    totalSecondsLast30Days = 0L,
                    booksStarted = 0,
                    booksFinished = 0,
                    currentStreakDays = 0,
                    longestStreakDays = 0,
                    lastEventDate = null,
                    revision = 1L,
                    updatedAt = 1_730_000_000_000L,
                    createdAt = 1_730_000_000_000L,
                    deletedAt = null,
                )
            val encoded = contractJson.encodeToString(UserStatsSyncPayload.serializer(), original)
            contractJson.decodeFromString(UserStatsSyncPayload.serializer(), encoded) shouldBe original
        }

        test("UserStatsSyncPayload round-trips for an active user (non-zero windows, streaks, lastEventDate non-null)") {
            val original =
                UserStatsSyncPayload(
                    id = "user-2",
                    totalSecondsAllTime = 864_000L,
                    totalSecondsLast7Days = 36_000L,
                    totalSecondsLast30Days = 144_000L,
                    booksStarted = 12,
                    booksFinished = 8,
                    currentStreakDays = 5,
                    longestStreakDays = 21,
                    lastEventDate = "2026-05-22",
                    revision = 99L,
                    updatedAt = 1_748_000_000_000L,
                    createdAt = 1_720_000_000_000L,
                    deletedAt = null,
                )
            val encoded = contractJson.encodeToString(UserStatsSyncPayload.serializer(), original)
            contractJson.decodeFromString(UserStatsSyncPayload.serializer(), encoded) shouldBe original
        }
    })
