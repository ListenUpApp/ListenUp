package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val payload =
    EntitySyncPayload(
        id = "e1",
        kind = EntityKind.LOCATION,
        name = "The Institute",
        descriptor = "school",
        parentId = "e0",
        homeSeriesId = "s1",
        homeBookId = null,
        imageRef = null,
        createdBy = "u1",
        updatedBy = "u2",
        revision = 7L,
        updatedAt = 200L,
        createdAt = 100L,
        deletedAt = null,
    )

class EntitySyncPayloadContractTest :
    FunSpec({
        test("EntitySyncPayload round-trips") {
            contractJson.decodeFromString<EntitySyncPayload>(contractJson.encodeToString(payload)) shouldBe payload
        }

        test("a payload carrying a kind this build doesn't know decodes as UNKNOWN") {
            val json = contractJson.encodeToString(payload).replace("\"LOCATION\"", "\"FACTION\"")
            contractJson.decodeFromString<EntitySyncPayload>(json).kind shouldBe EntityKind.UNKNOWN
        }

        test("EntityMutation variants round-trip polymorphically") {
            val upsert =
                EntityMutation.Upsert(
                    EntityUpsert(
                        id = EntityId("e1"),
                        kind = EntityKind.CHARACTER,
                        name = "Darrow",
                        descriptor = null,
                        parentId = null,
                        homeSeriesId = SeriesId("s1"),
                        homeBookId = null,
                    ),
                )
            listOf(upsert, EntityMutation.Delete).forEach { mutation ->
                contractJson.decodeFromString<EntityMutation>(
                    contractJson.encodeToString(EntityMutation.serializer(), mutation),
                ) shouldBe mutation
            }
        }

        test("EntityChange round-trips with both snapshots") {
            val change =
                EntityChange(
                    id = StoryWorldHistoryId("h1"),
                    entityId = EntityId("e1"),
                    op = StoryWorldOp.UPDATE,
                    actorId = "u1",
                    occurredAt = 9L,
                    before = payload,
                    after = payload.copy(name = "Institute"),
                )
            contractJson.decodeFromString<EntityChange>(contractJson.encodeToString(change)) shouldBe change
        }

        test("ENTITIES is a registered domain keyed 'entities'") {
            SyncDomains.ENTITIES.name shouldBe "entities"
            (SyncDomains.ENTITIES in SyncDomains.all) shouldBe true
        }
    })
