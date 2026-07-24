package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ActivityPayloadContractTest :
    FunSpec({

        test("ActivitySyncPayload round-trips a book-bearing row (deletedAt = null)") {
            val original =
                ActivitySyncPayload(
                    id = "act-1",
                    userId = "user-1",
                    type = "finished_book",
                    bookId = "book-1",
                    isReread = false,
                    durationMs = 0L,
                    milestoneValue = 0,
                    milestoneUnit = null,
                    shelfId = null,
                    shelfName = null,
                    occurredAt = 1_730_000_000_000L,
                    revision = 1L,
                    createdAt = 1_730_000_000_000L,
                    updatedAt = 1_730_000_000_000L,
                    deletedAt = null,
                )
            val encoded = contractJson.encodeToString(ActivitySyncPayload.serializer(), original)
            contractJson.decodeFromString(ActivitySyncPayload.serializer(), encoded) shouldBe original
        }

        test("ActivitySyncPayload round-trips a non-book milestone row") {
            val original =
                ActivitySyncPayload(
                    id = "act-2",
                    userId = "user-2",
                    type = "listening_milestone",
                    bookId = null,
                    isReread = false,
                    durationMs = 3_600_000L,
                    milestoneValue = 100,
                    milestoneUnit = "hours",
                    shelfId = null,
                    shelfName = null,
                    occurredAt = 1_730_001_000_000L,
                    revision = 7L,
                    createdAt = 1_730_001_000_000L,
                    updatedAt = 1_730_001_000_000L,
                    deletedAt = null,
                )
            val encoded = contractJson.encodeToString(ActivitySyncPayload.serializer(), original)
            contractJson.decodeFromString(ActivitySyncPayload.serializer(), encoded) shouldBe original
        }

        test("ActivitySyncPayload round-trips a shelf row with a tombstone") {
            val original =
                ActivitySyncPayload(
                    id = "act-3",
                    userId = "user-3",
                    type = "shelf_created",
                    bookId = null,
                    isReread = false,
                    durationMs = 0L,
                    milestoneValue = 0,
                    milestoneUnit = null,
                    shelfId = "shelf-9",
                    shelfName = "Winter Reads",
                    occurredAt = 1_730_002_000_000L,
                    revision = 12L,
                    createdAt = 1_730_002_000_000L,
                    updatedAt = 1_730_003_000_000L,
                    deletedAt = 1_730_003_000_000L,
                )
            val encoded = contractJson.encodeToString(ActivitySyncPayload.serializer(), original)
            contractJson.decodeFromString(ActivitySyncPayload.serializer(), encoded) shouldBe original
        }
    })
