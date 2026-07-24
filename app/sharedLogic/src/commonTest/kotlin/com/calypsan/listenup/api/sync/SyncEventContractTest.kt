package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.serializer

class SyncEventContractTest :
    FunSpec({

        test("Created round-trips with payload and clientOpId") {
            val original: SyncEvent<String> =
                SyncEvent.Created(
                    id = "abc",
                    revision = 42,
                    occurredAt = 1730000000000L,
                    clientOpId = "op-uuid-1",
                    payload = "hello",
                )
            val json = contractJson.encodeToString(SyncEvent.serializer(String.serializer()), original)
            val decoded = contractJson.decodeFromString(SyncEvent.serializer(String.serializer()), json)
            decoded shouldBe original
        }

        test("Updated round-trips with null clientOpId for server-originated writes") {
            val original: SyncEvent<String> =
                SyncEvent.Updated(
                    id = "abc",
                    revision = 43,
                    occurredAt = 1730000000001L,
                    clientOpId = null,
                    payload = "world",
                )
            val json = contractJson.encodeToString(SyncEvent.serializer(String.serializer()), original)
            val decoded = contractJson.decodeFromString(SyncEvent.serializer(String.serializer()), json)
            decoded shouldBe original
        }

        test("Deleted round-trips and carries no payload") {
            val original: SyncEvent<String> =
                SyncEvent.Deleted(
                    id = "abc",
                    revision = 44,
                    occurredAt = 1730000000002L,
                    clientOpId = "op-uuid-2",
                )
            val json = contractJson.encodeToString(SyncEvent.serializer(String.serializer()), original)
            val decoded = contractJson.decodeFromString(SyncEvent.serializer(String.serializer()), json)
            decoded shouldBe original
        }

        test("Stable @SerialName discriminators") {
            val created: SyncEvent<String> = SyncEvent.Created("a", 1, 10, null, "x")
            val updated: SyncEvent<String> = SyncEvent.Updated("a", 1, 10, null, "x")
            val deleted: SyncEvent<String> = SyncEvent.Deleted("a", 1, 10, null)
            contractJson
                .encodeToString(SyncEvent.serializer(String.serializer()), created)
                .contains("\"type\":\"SyncEvent.Created\"") shouldBe true
            contractJson
                .encodeToString(SyncEvent.serializer(String.serializer()), updated)
                .contains("\"type\":\"SyncEvent.Updated\"") shouldBe true
            contractJson
                .encodeToString(SyncEvent.serializer(String.serializer()), deleted)
                .contains("\"type\":\"SyncEvent.Deleted\"") shouldBe true
        }
    })
