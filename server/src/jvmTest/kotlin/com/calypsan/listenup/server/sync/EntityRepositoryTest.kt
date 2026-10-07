package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private val ACTOR = UserId("u1")

/** [EntityRepository]'s core writes: history atomic with the change, arrival-order overwrite, parent rules. */
class EntityRepositoryTest :
    FunSpec({
        test("create then edit records CREATE then UPDATE, with before and after snapshots") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Darrow"), ACTOR)
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Reaper"), ACTOR)

                    val history = repo.listHistory(EntityId("e1"))
                    history.map { it.op } shouldBe listOf(StoryWorldOp.UPDATE, StoryWorldOp.CREATE)
                    history[0].before.shouldNotBeNull().name shouldBe "Darrow"
                    history[0].after.shouldNotBeNull().name shouldBe "Reaper"
                    history[1].before.shouldBeNull()
                    history[0].actorId shouldBe "u1"
                }
            }
        }

        test("the server stamps its own clock, and the write that arrives last wins") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val serverNow = Instant.fromEpochMilliseconds(5_000_000L)
                val repo = entityRepository(clock = FixedClock(serverNow))
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "first", updatedAt = 300), ACTOR)
                    val later =
                        repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "second", updatedAt = 200), ACTOR)

                    val saved = later.shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>().data
                    saved.name shouldBe "second"
                    saved.updatedAt shouldBe serverNow.toEpochMilliseconds()
                    repo.listHistory(EntityId("e1")) shouldHaveSize 2
                }
            }
        }

        // Regression for next's race: the read and the write ran in two transactions, so a write could
        // record a `before` that another write had already overwritten — a torn history. Real threads,
        // many rounds: with one transaction, each write's `before` is exactly the state the other left.
        test("concurrent edits each record one history row whose before is what the other write left") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runBlocking(Dispatchers.IO) {
                    repeat(RACE_ROUNDS) { round ->
                        val id = "race-$round"
                        repo.upsertEntity(entityPayload(id, homeBookId = "b1", name = "v0"), ACTOR)
                        coroutineScope {
                            launch { repo.upsertEntity(entityPayload(id, homeBookId = "b1", name = "a"), ACTOR) }
                            launch { repo.upsertEntity(entityPayload(id, homeBookId = "b1", name = "b"), ACTOR) }
                        }

                        val updates = repo.listHistory(EntityId(id)).filter { it.op == StoryWorldOp.UPDATE }.reversed()
                        updates shouldHaveSize 2
                        updates.map { it.after.shouldNotBeNull().name } shouldContainExactlyInAnyOrder listOf("a", "b")
                        val (first, last) = updates
                        first.before.shouldNotBeNull().name shouldBe "v0"
                        last.before shouldBe first.after
                        repo.findById(EntityId(id)) shouldBe last.after
                    }
                }
            }
        }

        test("the history row is written in the same transaction as the change") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                driver.execute(null, "DROP TABLE story_world_history", 0)
                runTest {
                    shouldThrowAny { repo.upsertEntity(entityPayload("e1", homeBookId = "b1"), ACTOR) }
                }
                sql.entitiesQueries
                    .selectById("e1")
                    .executeAsOneOrNull()
                    .shouldBeNull()
            }
        }

        test("a parent of another kind, another home or that would close a loop is refused") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestBook("b2")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("mars", kind = EntityKind.LOCATION, homeBookId = "b1"), ACTOR)
                    repo.upsertEntity(
                        entityPayload("lykos", kind = EntityKind.LOCATION, homeBookId = "b1", parentId = "mars"),
                        ACTOR,
                    )
                    repo.upsertEntity(entityPayload("elsewhere", kind = EntityKind.LOCATION, homeBookId = "b2"), ACTOR)

                    repo
                        .upsertEntity(
                            entityPayload("darrow", kind = EntityKind.CHARACTER, homeBookId = "b1", parentId = "mars"),
                            ACTOR,
                        ).shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.InvalidParent>()
                    repo
                        .upsertEntity(
                            entityPayload("x", kind = EntityKind.LOCATION, homeBookId = "b1", parentId = "elsewhere"),
                            ACTOR,
                        ).shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.InvalidParent>()
                    repo
                        .upsertEntity(
                            entityPayload("mars", kind = EntityKind.LOCATION, homeBookId = "b1", parentId = "lykos"),
                            ACTOR,
                        ).shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.CycleDetected>()
                }
            }
        }

        test("a tombstone crosses the wire with no content") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Secret"), ACTOR)
                    repo.deleteEntity(EntityId("e1"), ACTOR)

                    val tombstone = repo.pullSince(userId = null, cursor = 0L, limit = 10, extraWhere = null).items.single()
                    tombstone.deletedAt.shouldNotBeNull()
                    tombstone.name shouldBe ""
                    tombstone.homeBookId.shouldBeNull()
                }
            }
        }
    })

private const val RACE_ROUNDS = 60
