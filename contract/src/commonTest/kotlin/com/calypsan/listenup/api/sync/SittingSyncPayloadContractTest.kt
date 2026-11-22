package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SittingSyncPayloadContractTest :
    FunSpec({
        val sitting =
            SittingSyncPayload(
                id = "sitting-1",
                userId = "darlene",
                bookId = "red-rising",
                startedAt = 1_730_000_000_000L,
                endedAt = 1_730_003_540_000L,
                listenedMs = 3_540_000L,
                beganRead = SittingStart.REREAD,
                finishedRead = true,
                revision = 42L,
                deletedAt = null,
            )

        test("a sitting round-trips") {
            val encoded = contractJson.encodeToString(SittingSyncPayload.serializer(), sitting)

            contractJson.decodeFromString(SittingSyncPayload.serializer(), encoded) shouldBe sitting
        }

        test("a tombstone round-trips") {
            val tombstone = sitting.copy(revision = 43L, deletedAt = 1_730_004_000_000L)
            val encoded = contractJson.encodeToString(SittingSyncPayload.serializer(), tombstone)

            contractJson.decodeFromString(SittingSyncPayload.serializer(), encoded) shouldBe tombstone
        }

        test("the start is written as a lower-case word") {
            contractJson.encodeToString(SittingSyncPayload.serializer(), sitting) shouldBe
                """{"id":"sitting-1","userId":"darlene","bookId":"red-rising","startedAt":1730000000000,""" +
                """"endedAt":1730003540000,"listenedMs":3540000,"beganRead":"reread","finishedRead":true,""" +
                """"revision":42}"""
        }

        test("a start this build has never heard of reads as none, so the sitting still shows") {
            val json =
                """{"id":"s","userId":"u","bookId":"b","startedAt":1,"endedAt":2,"listenedMs":1,""" +
                    """"beganRead":"resumed-from-a-friend","finishedRead":false,"revision":1}"""

            contractJson.decodeFromString(SittingSyncPayload.serializer(), json).beganRead shouldBe SittingStart.NONE
        }

        test("sittings are a sync domain") {
            SyncDomains.SITTINGS.name shouldBe "sittings"
            (SyncDomains.SITTINGS in SyncDomains.all) shouldBe true
        }
    })
