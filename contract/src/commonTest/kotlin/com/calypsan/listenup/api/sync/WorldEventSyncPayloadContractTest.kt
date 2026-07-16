package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.dto.world.EventsBatch
import com.calypsan.listenup.api.dto.world.WorldEventOp
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class WorldEventSyncPayloadContractTest :
    FunSpec({
        val base =
            WorldEventSyncPayload(
                id = "event-1",
                homeSeriesId = "series-1",
                homeBookId = null,
                bookId = "book-1",
                positionMs = 120_000L,
                type = WorldEventType.NOTE,
                text = "A quiet moment before the storm.",
                subjectEntityId = null,
                objectEntityId = null,
                mentionIds = emptyList(),
                source = WorldEventSource.MANUAL,
                trackId = null,
                trackVersion = null,
                revision = 1L,
                updatedAt = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L,
                deletedAt = null,
            )

        test("WorldEventSyncPayload round-trips every WorldEventType value") {
            WorldEventType.entries.forEach { type ->
                val payload = base.copy(type = type)
                val json = contractJson.encodeToString(WorldEventSyncPayload.serializer(), payload)
                val decoded = contractJson.decodeFromString(WorldEventSyncPayload.serializer(), json)
                decoded shouldBe payload
                decoded.type shouldBe type
            }
        }

        test("WorldEventSyncPayload round-trips with both bookId and positionMs null (no book anchor)") {
            val payload = base.copy(bookId = null, positionMs = null)
            val json = contractJson.encodeToString(WorldEventSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(WorldEventSyncPayload.serializer(), json)
            decoded shouldBe payload
            decoded.bookId shouldBe null
            decoded.positionMs shouldBe null
        }

        test("WorldEventSyncPayload round-trips with bookId set and positionMs null") {
            val payload = base.copy(bookId = "book-1", positionMs = null)
            val json = contractJson.encodeToString(WorldEventSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(WorldEventSyncPayload.serializer(), json)
            decoded shouldBe payload
        }

        test("WorldEventSyncPayload round-trips with bookId null and positionMs set") {
            val payload = base.copy(bookId = null, positionMs = 5_000L)
            val json = contractJson.encodeToString(WorldEventSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(WorldEventSyncPayload.serializer(), json)
            decoded shouldBe payload
        }

        test("WorldEventSyncPayload round-trips with both bookId and positionMs set") {
            val payload = base.copy(bookId = "book-1", positionMs = 42_000L)
            val json = contractJson.encodeToString(WorldEventSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(WorldEventSyncPayload.serializer(), json)
            decoded shouldBe payload
        }

        test("WorldEventSyncPayload round-trips text carrying mention tokens byte-exact") {
            val mentionText =
                "Then ${MentionTokens.token("entity-42", "The Stranger in Grey")} entered the room."
            val payload =
                base.copy(
                    text = mentionText,
                    subjectEntityId = "entity-42",
                    mentionIds = listOf("entity-42"),
                )
            val json = contractJson.encodeToString(WorldEventSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(WorldEventSyncPayload.serializer(), json)
            decoded shouldBe payload
            decoded.text shouldBe mentionText
        }

        test("WorldEventSyncPayload tolerates an unknown field on decode (forward compat)") {
            val full =
                contractJson
                    .parseToJsonElement(
                        contractJson.encodeToString(WorldEventSyncPayload.serializer(), base),
                    ).jsonObject
            val withExtraField =
                JsonObject(
                    full.toMutableMap().apply {
                        put("aFieldFromTheFuture", JsonPrimitive("some value a newer server added"))
                    },
                )
            val json = contractJson.encodeToString(withExtraField)
            val decoded = contractJson.decodeFromString(WorldEventSyncPayload.serializer(), json)
            decoded shouldBe base
        }

        test("WorldEventType wire tokens are pinned") {
            contractJson.encodeToString(WorldEventType.serializer(), WorldEventType.NOTE) shouldBe "\"NOTE\""
            contractJson.encodeToString(
                WorldEventType.serializer(),
                WorldEventType.ENTERS_SCENE,
            ) shouldBe "\"ENTERS_SCENE\""
            contractJson.encodeToString(
                WorldEventType.serializer(),
                WorldEventType.MOVES_TO,
            ) shouldBe "\"MOVES_TO\""
            contractJson.encodeToString(
                WorldEventType.serializer(),
                WorldEventType.RELATIONSHIP_CHANGE,
            ) shouldBe "\"RELATIONSHIP_CHANGE\""
        }

        test("WorldEventSource wire tokens are pinned") {
            contractJson.encodeToString(WorldEventSource.serializer(), WorldEventSource.MANUAL) shouldBe "\"MANUAL\""
            contractJson.encodeToString(
                WorldEventSource.serializer(),
                WorldEventSource.IMPORTED,
            ) shouldBe "\"IMPORTED\""
        }

        test("EventsBatch round-trips a mix of Upsert and Delete ops") {
            val batch =
                EventsBatch(
                    ops =
                        listOf(
                            WorldEventOp.Upsert(
                                upsert =
                                    WorldEventUpsert(
                                        id = "event-1",
                                        homeSeriesId = "series-1",
                                        homeBookId = null,
                                        bookId = "book-1",
                                        positionMs = 120_000L,
                                        type = WorldEventType.NOTE,
                                        text = "A quiet moment before the storm.",
                                        subjectEntityId = null,
                                        objectEntityId = null,
                                    ),
                            ),
                            WorldEventOp.Delete(id = "event-2"),
                        ),
                )
            val json = contractJson.encodeToString(EventsBatch.serializer(), batch)
            val decoded = contractJson.decodeFromString(EventsBatch.serializer(), json)
            decoded shouldBe batch
        }
    })
