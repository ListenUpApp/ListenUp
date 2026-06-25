package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PublicProfileSyncPayloadTest :
    FunSpec({
        test("round-trips through contractJson preserving every field") {
            val payload =
                PublicProfileSyncPayload(
                    id = "user-1",
                    displayName = "Ada Lovelace",
                    avatarType = "image",
                    tagline = null,
                    totalSecondsAllTime = 123_456L,
                    totalSecondsLast7Days = 7_000L,
                    totalSecondsLast30Days = 30_000L,
                    totalSecondsLast365Days = 365_000L,
                    booksFinished = 12,
                    currentStreakDays = 5,
                    longestStreakDays = 40,
                    revision = 99L,
                    updatedAt = 1_700_000_000_000L,
                    createdAt = 1_600_000_000_000L,
                    deletedAt = null,
                )
            val json = contractJson.encodeToString(PublicProfileSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(PublicProfileSyncPayload.serializer(), json)
            decoded shouldBe payload
        }

        test("round-trips a tombstoned row preserving the non-null deletedAt sentinel") {
            // Tombstone propagation is load-bearing for this global prune-on-delete domain:
            // a deleted user's row must survive the wire with deletedAt set so clients prune it.
            val tombstoned =
                PublicProfileSyncPayload(
                    id = "user-2",
                    displayName = "Grace Hopper",
                    avatarType = "auto",
                    tagline = null,
                    totalSecondsAllTime = 0L,
                    totalSecondsLast7Days = 0L,
                    totalSecondsLast30Days = 0L,
                    totalSecondsLast365Days = 0L,
                    booksFinished = 0,
                    currentStreakDays = 0,
                    longestStreakDays = 0,
                    revision = 100L,
                    updatedAt = 1_700_000_000_000L,
                    createdAt = 1_600_000_000_000L,
                    deletedAt = 1_700_000_500_000L,
                )
            val json = contractJson.encodeToString(PublicProfileSyncPayload.serializer(), tombstoned)
            val decoded = contractJson.decodeFromString(PublicProfileSyncPayload.serializer(), json)
            decoded shouldBe tombstoned
        }

        test("avatarUpdatedAt round-trips and defaults to 0 for legacy JSON") {
            val payload =
                PublicProfileSyncPayload(
                    id = "user-1",
                    displayName = "Ada Lovelace",
                    avatarType = "image",
                    tagline = null,
                    totalSecondsAllTime = 123_456L,
                    totalSecondsLast7Days = 7_000L,
                    totalSecondsLast30Days = 30_000L,
                    totalSecondsLast365Days = 365_000L,
                    booksFinished = 12,
                    currentStreakDays = 5,
                    longestStreakDays = 40,
                    revision = 99L,
                    updatedAt = 1_700_000_000_000L,
                    createdAt = 1_600_000_000_000L,
                    deletedAt = null,
                ).copy(avatarUpdatedAt = 1_717_000_000_000L)
            val encoded = contractJson.encodeToString(PublicProfileSyncPayload.serializer(), payload)
            contractJson.decodeFromString(PublicProfileSyncPayload.serializer(), encoded).avatarUpdatedAt shouldBe 1_717_000_000_000L

            // Back-compat: JSON without the field decodes to 0.
            val legacy = encoded.replace(""","avatarUpdatedAt":1717000000000""", "")
            contractJson.decodeFromString(PublicProfileSyncPayload.serializer(), legacy).avatarUpdatedAt shouldBe 0L
        }

        test("round-trips the windowed books and streak metrics") {
            val payload =
                PublicProfileSyncPayload(
                    id = "user-3",
                    displayName = "Katherine Johnson",
                    avatarType = "auto",
                    tagline = null,
                    totalSecondsAllTime = 100L,
                    totalSecondsLast7Days = 10L,
                    totalSecondsLast30Days = 20L,
                    totalSecondsLast365Days = 30L,
                    booksFinished = 9,
                    currentStreakDays = 2,
                    longestStreakDays = 8,
                    booksFinishedLast7Days = 1,
                    booksFinishedLast30Days = 3,
                    booksFinishedLast365Days = 7,
                    longestStreakLast7Days = 2,
                    longestStreakLast30Days = 4,
                    longestStreakLast365Days = 6,
                    revision = 5L,
                    updatedAt = 1_700_000_000_000L,
                    createdAt = 1_600_000_000_000L,
                    deletedAt = null,
                )
            val json = contractJson.encodeToString(PublicProfileSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(PublicProfileSyncPayload.serializer(), json)
            decoded shouldBe payload
            decoded.booksFinishedLast30Days shouldBe 3
            decoded.longestStreakLast365Days shouldBe 6
        }
    })
