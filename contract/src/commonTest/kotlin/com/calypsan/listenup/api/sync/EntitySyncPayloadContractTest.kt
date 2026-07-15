package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class EntitySyncPayloadContractTest :
    FunSpec({
        val seriesHomed =
            EntitySyncPayload(
                id = "entity-1",
                kind = EntityKind.CHARACTER,
                name = "The Stranger in Grey",
                homeSeriesId = "series-1",
                homeBookId = null,
                imageRef = null,
                revision = 1L,
                updatedAt = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L,
                deletedAt = null,
            )

        val bookHomed =
            seriesHomed.copy(
                id = "entity-2",
                homeSeriesId = null,
                homeBookId = "book-1",
                imageRef = "ref-abc",
                revision = 2L,
            )

        test("EntitySyncPayload round-trips a series-homed entity (homeBookId null)") {
            val json = contractJson.encodeToString(EntitySyncPayload.serializer(), seriesHomed)
            val decoded = contractJson.decodeFromString(EntitySyncPayload.serializer(), json)
            decoded shouldBe seriesHomed
            decoded.homeSeriesId shouldBe "series-1"
            decoded.homeBookId shouldBe null
        }

        test("EntitySyncPayload round-trips a book-homed entity (homeSeriesId null)") {
            val json = contractJson.encodeToString(EntitySyncPayload.serializer(), bookHomed)
            val decoded = contractJson.decodeFromString(EntitySyncPayload.serializer(), json)
            decoded shouldBe bookHomed
            decoded.homeSeriesId shouldBe null
            decoded.homeBookId shouldBe "book-1"
        }

        test("EntitySyncPayload tolerates an unknown field on decode (forward compat)") {
            val full =
                contractJson
                    .parseToJsonElement(
                        contractJson.encodeToString(EntitySyncPayload.serializer(), bookHomed),
                    ).jsonObject
            val withExtraField =
                JsonObject(
                    full.toMutableMap().apply {
                        put("aFieldFromTheFuture", JsonPrimitive("some value a newer server added"))
                    },
                )
            val json = contractJson.encodeToString(withExtraField)
            val decoded = contractJson.decodeFromString(EntitySyncPayload.serializer(), json)
            decoded shouldBe bookHomed
        }

        test("EntityKind wire tokens are pinned") {
            contractJson.encodeToString(EntityKind.serializer(), EntityKind.CHARACTER) shouldBe "\"CHARACTER\""
            contractJson.encodeToString(EntityKind.serializer(), EntityKind.LOCATION) shouldBe "\"LOCATION\""
            contractJson.encodeToString(EntityKind.serializer(), EntityKind.ITEM) shouldBe "\"ITEM\""
        }
    })
