package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.EventsBatch
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val payload =
    WorldEventSyncPayload(
        id = "w1",
        homeSeriesId = "s1",
        bookId = "b1",
        positionMs = 8_040_000L,
        type = WorldEventType.JOINS,
        text = "[[e:c1|Darrow]] is drafted into House Mars.",
        detail = "Primus",
        subjectEntityId = "c1",
        objectEntityId = "g1",
        mentionIds = listOf("c1", "g1"),
        createdBy = "u1",
        updatedBy = "u2",
        revision = 7L,
        updatedAt = 200L,
        createdAt = 100L,
    )

class WorldEventSyncPayloadContractTest :
    FunSpec({
        test("WorldEventSyncPayload round-trips") {
            contractJson.decodeFromString<WorldEventSyncPayload>(contractJson.encodeToString(payload)) shouldBe payload
        }

        test("a payload carrying a type this build doesn't know decodes as UNKNOWN") {
            val json = contractJson.encodeToString(payload).replace("\"JOINS\"", "\"MARRIES\"")
            contractJson.decodeFromString<WorldEventSyncPayload>(json).type shouldBe WorldEventType.UNKNOWN
        }

        test("a batch of both op variants round-trips polymorphically") {
            val batch =
                EventsBatch(
                    listOf(
                        WorldEventOp.Upsert(
                            WorldEventUpsert(
                                id = WorldEventId("w1"),
                                type = WorldEventType.BELONGS_TO,
                                homeSeriesId = SeriesId("s1"),
                                bookId = BookId("b1"),
                                positionMs = 0L,
                                subjectEntityId = EntityId("c1"),
                                objectEntityId = EntityId("p1"),
                            ),
                        ),
                        WorldEventOp.Delete(WorldEventId("w2")),
                    ),
                )
            contractJson.decodeFromString<EventsBatch>(contractJson.encodeToString(batch)) shouldBe batch
        }

        test("WorldEventChange round-trips with both snapshots") {
            val change =
                WorldEventChange(
                    id = StoryWorldHistoryId("h1"),
                    eventId = WorldEventId("w1"),
                    op = StoryWorldOp.UPDATE,
                    actorId = "u1",
                    occurredAt = 9L,
                    before = payload,
                    after = payload.copy(detail = "Lancer"),
                )
            contractJson.decodeFromString<WorldEventChange>(contractJson.encodeToString(change)) shouldBe change
        }

        test("WORLD_EVENTS is a registered domain keyed 'world_events'") {
            SyncDomains.WORLD_EVENTS.name shouldBe "world_events"
            (SyncDomains.WORLD_EVENTS in SyncDomains.all) shouldBe true
        }
    })
