package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.domain.storyworld.MentionTokens
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.eventUpsert
import com.calypsan.listenup.server.testing.record
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.testing.worldEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val ACTOR = UserId("u1")

/** The Red Rising saga (books b1, b2) with a cast, plus a standalone book "solo" with its own character. */
private class World(
    val events: WorldEventRepository,
    val entities: EntityRepository,
    val saga: SeriesId,
) {
    suspend fun refusal(upsert: WorldEventUpsert): AppError =
        events
            .applyBatch(listOf(WorldEventOp.Upsert(upsert)), ACTOR)
            .shouldBeInstanceOf<AppResult.Failure>()
            .error
}

private suspend fun SqlTestDatabases.world(): World {
    sql.seedTestLibraryAndFolder()
    val saga = seedSeriesWithBooks("Red Rising", "b1", "b2")
    sql.seedTestBook("solo")
    val entities = entityRepository()
    entities.upsertEntity(entityPayload("darrow", kind = EntityKind.CHARACTER, homeSeriesId = saga.value), ACTOR)
    entities.upsertEntity(entityPayload("mars", kind = EntityKind.GROUP, homeSeriesId = saga.value), ACTOR)
    entities.upsertEntity(entityPayload("reds", kind = EntityKind.PEOPLE, homeSeriesId = saga.value), ACTOR)
    entities.upsertEntity(entityPayload("institute", kind = EntityKind.LOCATION, homeSeriesId = saga.value), ACTOR)
    entities.upsertEntity(entityPayload("stranger", kind = EntityKind.CHARACTER, homeBookId = "solo"), ACTOR)
    return World(worldEventRepository(), entities, saga)
}

/** Batched writes: one transaction, atomic history, server authorship and mentions, integrity inside the write. */
class WorldEventRepositoryTest :
    FunSpec({
        test("a create records CREATE and reads back with the server's authorship and mentions") {
            withSqlDatabase {
                runTest {
                    val world = world()
                    world.events.record(
                        eventUpsert(
                            "w1",
                            type = WorldEventType.JOINS,
                            text = "",
                            homeSeriesId = world.saga.value,
                            bookId = "b1",
                            positionMs = 0L,
                            subject = "darrow",
                            obj = "mars",
                            detail = "Primus",
                        ),
                    )

                    val saved = world.events.findById(WorldEventId("w1")).shouldNotBeNull()
                    saved.type shouldBe WorldEventType.JOINS
                    saved.detail shouldBe "Primus"
                    saved.positionMs shouldBe 0L
                    saved.createdBy shouldBe "u1"
                    saved.updatedBy shouldBe "u1"
                    saved.mentionIds shouldBe listOf("darrow", "mars")
                    val change = world.events.listHistory(WorldEventId("w1")).single()
                    change.op shouldBe StoryWorldOp.CREATE
                    change.before.shouldBeNull()
                    change.after shouldBe saved
                }
            }
        }

        test("an edit records UPDATE over the row it replaced, keeps the creator, and can't change home") {
            withSqlDatabase {
                runTest {
                    val world = world()
                    world.events.record(eventUpsert("w1", text = "first", homeSeriesId = world.saga.value), UserId("u1"))
                    world.events.record(eventUpsert("w1", text = "second", homeSeriesId = world.saga.value), UserId("u2"))

                    val history = world.events.listHistory(WorldEventId("w1"))
                    history.map { it.op } shouldBe listOf(StoryWorldOp.UPDATE, StoryWorldOp.CREATE)
                    history.first().before?.text shouldBe "first"
                    history.first().after?.text shouldBe "second"
                    val saved = world.events.findById(WorldEventId("w1")).shouldNotBeNull()
                    saved.createdBy shouldBe "u1"
                    saved.updatedBy shouldBe "u2"
                    world.refusal(eventUpsert("w1", text = "moved", homeBookId = "solo")).shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("a batch lands whole or not at all: one refused op leaves no row and no history") {
            withSqlDatabase {
                runTest {
                    val world = world()
                    val refused =
                        world.events.applyBatch(
                            listOf(
                                WorldEventOp.Upsert(eventUpsert("ok", homeSeriesId = world.saga.value)),
                                WorldEventOp.Upsert(
                                    eventUpsert(
                                        "bad",
                                        type = WorldEventType.JOINS,
                                        text = "",
                                        homeSeriesId = world.saga.value,
                                        subject = "darrow",
                                        obj = "reds",
                                    ),
                                ),
                            ),
                            ACTOR,
                        )

                    refused.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<WorldEventError.WrongEntityKind>()
                    world.events.findById(WorldEventId("ok")).shouldBeNull()
                    world.events.listHistory(WorldEventId("ok")).shouldBeEmpty()
                    world.events
                        .pullSince(userId = null, cursor = 0L, limit = 50, extraWhere = null)
                        .items
                        .shouldBeEmpty()
                }
            }
        }

        test("mentions are server-derived and stay inside the event's world") {
            withSqlDatabase {
                runTest {
                    val world = world()
                    val text =
                        "${MentionTokens.token("darrow", "Darrow")} meets ${MentionTokens.token("stranger", "Stranger")} " +
                            "and ${MentionTokens.token("ghost", "Ghost")}"
                    world.events.record(
                        eventUpsert("w1", type = WorldEventType.NOTE, text = text, homeSeriesId = world.saga.value, obj = "institute"),
                    )

                    world.events
                        .findById(WorldEventId("w1"))
                        .shouldNotBeNull()
                        .mentionIds shouldBe listOf("darrow", "institute")
                    world.events.listLiveMentioning(EntityId("institute")).map { it.id } shouldBe listOf("w1")
                    world.events.listLiveMentioning(EntityId("stranger")).shouldBeEmpty()
                }
            }
        }

        test("a delete records DELETE and drops its mentions; a deleted event stays deleted") {
            withSqlDatabase {
                runTest {
                    val world = world()
                    world.events.record(
                        eventUpsert("w1", text = MentionTokens.token("darrow", "Darrow"), homeSeriesId = world.saga.value),
                    )

                    world.events.applyBatch(listOf(WorldEventOp.Delete(WorldEventId("w1"))), ACTOR) shouldBe AppResult.Success(Unit)

                    val tombstone = world.events.findById(WorldEventId("w1")).shouldNotBeNull()
                    tombstone.deletedAt.shouldNotBeNull()
                    tombstone.mentionIds.shouldBeEmpty()
                    world.events
                        .listHistory(WorldEventId("w1"))
                        .first()
                        .op shouldBe StoryWorldOp.DELETE
                    world.events
                        .applyBatch(listOf(WorldEventOp.Delete(WorldEventId("w1"))), ACTOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<WorldEventError.NotFound>()
                    world.refusal(eventUpsert("w1", homeSeriesId = world.saga.value)).shouldBeInstanceOf<WorldEventError.NotFound>()
                }
            }
        }

        test("integrity is decided inside the write: anchor, participants and UNKNOWN on create") {
            withSqlDatabase {
                runTest {
                    val world = world()
                    val saga = world.saga.value
                    world
                        .refusal(eventUpsert("a", homeSeriesId = saga, bookId = "solo", positionMs = 1L))
                        .shouldBeInstanceOf<WorldEventError.InvalidAnchor>()
                    world
                        .refusal(eventUpsert("b", homeBookId = "solo", bookId = "b1", positionMs = 1L))
                        .shouldBeInstanceOf<WorldEventError.InvalidAnchor>()
                    world
                        .refusal(eventUpsert("c", type = WorldEventType.ENTERS_SCENE, text = "", homeSeriesId = saga, subject = "stranger"))
                        .shouldBeInstanceOf<WorldEventError.EntityNotInWorld>()
                    world.entities.deleteEntity(EntityId("institute"), ACTOR)
                    world
                        .refusal(
                            eventUpsert(
                                "d",
                                type = WorldEventType.MOVES_TO,
                                text = "",
                                homeSeriesId = saga,
                                subject = "darrow",
                                obj = "institute",
                            ),
                        ).shouldBeInstanceOf<WorldEventError.EntityNotInWorld>()
                    world
                        .refusal(eventUpsert("e", type = WorldEventType.UNKNOWN, homeSeriesId = saga))
                        .shouldBeInstanceOf<ValidationError>()
                        .field shouldBe "type"
                }
            }
        }

        test("an edit may keep a participant deleted since, but may not newly name a deleted one") {
            withSqlDatabase {
                runTest {
                    val world = world()
                    val saga = world.saga.value

                    fun joins(
                        text: String,
                        obj: String,
                    ) = eventUpsert("w1", type = WorldEventType.JOINS, text = text, homeSeriesId = saga, subject = "darrow", obj = obj)
                    world.entities.upsertEntity(entityPayload("sons", kind = EntityKind.GROUP, homeSeriesId = saga), ACTOR)
                    world.events.record(joins(text = "", obj = "mars"))
                    world.entities.deleteEntity(EntityId("mars"), ACTOR)
                    world.entities.deleteEntity(EntityId("sons"), ACTOR)

                    world.events.record(joins(text = "a typo, fixed", obj = "mars"))
                    world.events
                        .findById(WorldEventId("w1"))
                        .shouldNotBeNull()
                        .text shouldBe "a typo, fixed"
                    world.refusal(joins(text = "", obj = "sons")).shouldBeInstanceOf<WorldEventError.EntityNotInWorld>()
                }
            }
        }

        test("an edit carrying UNKNOWN keeps the stored type") {
            withSqlDatabase {
                runTest {
                    val world = world()
                    val saga = world.saga.value
                    world.events.record(
                        eventUpsert(
                            "w1",
                            type = WorldEventType.BELONGS_TO,
                            text = "",
                            homeSeriesId = saga,
                            subject = "darrow",
                            obj = "reds",
                        ),
                    )
                    world.events.record(
                        eventUpsert(
                            "w1",
                            type = WorldEventType.UNKNOWN,
                            text = "revealed",
                            homeSeriesId = saga,
                            subject = "darrow",
                            obj = "reds",
                        ),
                    )

                    val saved = world.events.findById(WorldEventId("w1")).shouldNotBeNull()
                    saved.type shouldBe WorldEventType.BELONGS_TO
                    saved.text shouldBe "revealed"
                }
            }
        }
    })
