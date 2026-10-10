package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.domain.storyworld.MentionTokens
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.eventUpsert
import com.calypsan.listenup.server.testing.record
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.testing.worldEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val ACTOR = UserId("u1")
private val W1 = WorldEventId("w1")

/** Revert restores an earlier state as a new, recorded, revertible write. */
class WorldEventRepositoryRevertTest :
    FunSpec({
        test("reverting a DELETE revives the event, its text and its mentions") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val saga = seedSeriesWithBooks("Red Rising", "b1")
                    entityRepository().upsertEntity(entityPayload("darrow", homeSeriesId = saga.value), ACTOR)
                    val repo = worldEventRepository()
                    repo.record(eventUpsert("w1", text = MentionTokens.token("darrow", "Darrow"), homeSeriesId = saga.value))
                    repo.applyBatch(listOf(WorldEventOp.Delete(W1)), ACTOR)
                    val deletion = repo.listHistory(W1).first()

                    val reverted = repo.revert(deletion.id, ACTOR).shouldBeInstanceOf<AppResult.Success<WorldEventChange>>().data

                    reverted.op shouldBe StoryWorldOp.REVERT
                    val revived = repo.findById(W1).shouldNotBeNull()
                    revived.deletedAt.shouldBeNull()
                    revived.mentionIds shouldBe listOf("darrow")
                }
            }
        }

        test("reverting a CREATE deletes, and that revert can itself be reverted") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val saga = seedSeriesWithBooks("Red Rising", "b1")
                    val repo = worldEventRepository()
                    repo.record(eventUpsert("w1", homeSeriesId = saga.value))
                    val creation = repo.listHistory(W1).single()

                    val undoCreate = repo.revert(creation.id, ACTOR).shouldBeInstanceOf<AppResult.Success<WorldEventChange>>().data
                    repo
                        .findById(W1)
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()

                    repo.revert(undoCreate.id, ACTOR).shouldBeInstanceOf<AppResult.Success<WorldEventChange>>()
                    repo
                        .findById(W1)
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()
                }
            }
        }

        test("reverting an UPDATE restores the earlier type, text and anchor") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val saga = seedSeriesWithBooks("Red Rising", "b1", "b2")
                    val repo = worldEventRepository()
                    repo.record(eventUpsert("w1", text = "early", homeSeriesId = saga.value, bookId = "b1", positionMs = 10L))
                    repo.record(eventUpsert("w1", text = "late", homeSeriesId = saga.value, bookId = "b2", positionMs = 20L))
                    val update = repo.listHistory(W1).first()

                    repo.revert(update.id, ACTOR).shouldBeInstanceOf<AppResult.Success<WorldEventChange>>()

                    val restored = repo.findById(W1).shouldNotBeNull()
                    restored.text shouldBe "early"
                    restored.bookId shouldBe "b1"
                    restored.positionMs shouldBe 10L
                }
            }
        }

        test("a revert may restore a participant deleted since; undo isn't blocked by a later delete") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val saga = seedSeriesWithBooks("Red Rising", "b1")
                    val entities = entityRepository()
                    entities.upsertEntity(entityPayload("darrow", kind = EntityKind.CHARACTER, homeSeriesId = saga.value), ACTOR)
                    entities.upsertEntity(entityPayload("mars", kind = EntityKind.GROUP, homeSeriesId = saga.value), ACTOR)
                    val repo = worldEventRepository()
                    repo.record(
                        eventUpsert(
                            "w1",
                            type = WorldEventType.JOINS,
                            text = "",
                            homeSeriesId = saga.value,
                            subject = "darrow",
                            obj = "mars",
                        ),
                    )
                    repo.applyBatch(listOf(WorldEventOp.Delete(W1)), ACTOR)
                    entities.deleteEntity(EntityId("mars"), ACTOR)

                    repo.revert(repo.listHistory(W1).first().id, ACTOR).shouldBeInstanceOf<AppResult.Success<WorldEventChange>>()
                    repo.findById(W1).shouldNotBeNull().objectEntityId shouldBe "mars"
                }
            }
        }

        test("an unknown change is HistoryNotFound, and an entity's change is not an event's") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val saga = seedSeriesWithBooks("Red Rising", "b1")
                    val entities = entityRepository()
                    entities.upsertEntity(entityPayload("darrow", homeSeriesId = saga.value), ACTOR)
                    val entityChange = entities.listHistory(EntityId("darrow")).single().id
                    val repo = worldEventRepository()

                    repo
                        .revert(StoryWorldHistoryId("nope"), ACTOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<WorldEventError.HistoryNotFound>()
                    repo
                        .revert(entityChange, ACTOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<WorldEventError.HistoryNotFound>()
                }
            }
        }
    })
