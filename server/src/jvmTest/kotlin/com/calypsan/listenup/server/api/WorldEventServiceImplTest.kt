package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.EventsBatch
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.domain.storyworld.WorldEventRules
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.eventUpsert
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.testing.worldEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun as_(
    userId: String,
    role: UserRole,
) = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("s-$userId"), role) }

private fun batchOf(vararg upserts: WorldEventUpsert) = EventsBatch(upserts.map { WorldEventOp.Upsert(it) })

private fun AppResult<*>.failure(): AppError = shouldBeInstanceOf<AppResult.Failure>().error

/** "open" is visible to the members; "hidden" (same series) and "hidden2" (series Sealed) only to admins. */
private class EventWorld(
    val service: WorldEventServiceImpl,
    val redRising: SeriesId,
    val sealed: SeriesId,
) {
    fun asMember(userId: String = "member") = service.copyWith(as_(userId, UserRole.MEMBER))

    fun asRoot() = service.copyWith(as_("root", UserRole.ROOT))

    suspend fun newestChange(eventId: String): WorldEventChange =
        asRoot()
            .listHistory(WorldEventId(eventId))
            .shouldBeInstanceOf<AppResult.Success<List<WorldEventChange>>>()
            .data
            .first()
}

private suspend fun SqlTestDatabases.storyWorld(): EventWorld {
    sql.seedTestLibraryAndFolder()
    listOf("member", "nocontrib").forEach { sql.seedTestUser(it) }
    sql.seedTestUser("root", UserRoleColumn.ROOT)
    val redRising = seedSeriesWithBooks("Red Rising", "open", "hidden")
    val sealed = seedSeriesWithBooks("Sealed", "hidden2")
    listOf("member", "nocontrib").forEach { makeBookAccessible(sql, driver, bookId = "open", viewerId = it) }
    sql.usersQueries.updateStoryWorldPermissionFlags(can_contribute_story_world = 0L, can_curate_story_world = 0L, id = "nocontrib")
    val entities = entityRepository()
    entities.upsertEntity(entityPayload("darrow", kind = EntityKind.CHARACTER, homeSeriesId = redRising.value), UserId("root"))
    entities.upsertEntity(entityPayload("mars", kind = EntityKind.GROUP, homeSeriesId = redRising.value), UserId("root"))
    val service = WorldEventServiceImpl(worldEventRepository(), PermissionPolicy(sql), BookAccessPolicy(sql, driver))
    return EventWorld(service, redRising, sealed)
}

/** [WorldEventServiceImpl]'s permission × visibility matrix and its batch rules. */
class WorldEventServiceImplTest :
    FunSpec({
        test("contribute: members record by default; a member without the flag is denied") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    world.asMember().applyBatch(batchOf(eventUpsert("w1", homeSeriesId = world.redRising.value))) shouldBe
                        AppResult.Success(Unit)
                    world
                        .asMember("nocontrib")
                        .applyBatch(batchOf(eventUpsert("w2", homeSeriesId = world.redRising.value)))
                        .failure()
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("a hidden home is NotFound; a hidden anchor is InvalidAnchor; an event anchored out of sight is untouchable") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val member = world.asMember()
                    member
                        .applyBatch(batchOf(eventUpsert("w1", homeSeriesId = world.sealed.value)))
                        .failure()
                        .shouldBeInstanceOf<WorldEventError.NotFound>()
                    member
                        .applyBatch(batchOf(eventUpsert("w2", homeSeriesId = world.redRising.value, bookId = "hidden", positionMs = 1L)))
                        .failure()
                        .shouldBeInstanceOf<WorldEventError.InvalidAnchor>()

                    world.asRoot().applyBatch(
                        batchOf(eventUpsert("secret", homeSeriesId = world.redRising.value, bookId = "hidden", positionMs = 1L)),
                    ) shouldBe AppResult.Success(Unit)
                    member
                        .applyBatch(batchOf(eventUpsert("secret", text = "edited", homeSeriesId = world.redRising.value)))
                        .failure()
                        .shouldBeInstanceOf<WorldEventError.NotFound>()
                    member
                        .applyBatch(EventsBatch(listOf(WorldEventOp.Delete(WorldEventId("secret")))))
                        .failure()
                        .shouldBeInstanceOf<WorldEventError.NotFound>()
                    member.listHistory(WorldEventId("secret")).failure().shouldBeInstanceOf<WorldEventError.NotFound>()
                }
            }
        }

        test("a batch carries 1 to MAX_BATCH ops, each upsert with exactly one home") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val member = world.asMember()
                    member.applyBatch(EventsBatch(emptyList())).failure().shouldBeInstanceOf<ValidationError>()
                    val tooMany = (0..WorldEventRules.MAX_BATCH).map { eventUpsert("w$it", homeSeriesId = world.redRising.value) }
                    member.applyBatch(batchOf(*tooMany.toTypedArray())).failure().shouldBeInstanceOf<ValidationError>()
                    member
                        .applyBatch(batchOf(eventUpsert("w1", homeSeriesId = world.redRising.value, homeBookId = "open")))
                        .failure()
                        .shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("listings leave out every event whose home or anchor the member can't see") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val saga = world.redRising.value
                    world.asRoot().applyBatch(
                        batchOf(
                            eventUpsert("free", type = WorldEventType.ENTERS_SCENE, text = "", homeSeriesId = saga, subject = "darrow"),
                            eventUpsert(
                                "at-open",
                                type = WorldEventType.ENTERS_SCENE,
                                text = "",
                                homeSeriesId = saga,
                                bookId = "open",
                                positionMs = 1L,
                                subject = "darrow",
                            ),
                            eventUpsert(
                                "at-hidden",
                                type = WorldEventType.ENTERS_SCENE,
                                text = "",
                                homeSeriesId = saga,
                                bookId = "hidden",
                                positionMs = 1L,
                                subject = "darrow",
                            ),
                        ),
                    ) shouldBe AppResult.Success(Unit)
                    val member = world.asMember()

                    member
                        .listEventsForSeries(world.redRising)
                        .shouldBeInstanceOf<AppResult.Success<List<WorldEventSyncPayload>>>()
                        .data
                        .map { it.id }
                        .toSet() shouldBe setOf("free", "at-open")
                    member
                        .listEventsForEntity(EntityId("darrow"))
                        .shouldBeInstanceOf<AppResult.Success<List<WorldEventSyncPayload>>>()
                        .data
                        .map { it.id }
                        .toSet() shouldBe setOf("free", "at-open")
                    member
                        .listEventsForSeries(world.sealed)
                        .shouldBeInstanceOf<AppResult.Success<List<WorldEventSyncPayload>>>()
                        .data
                        .shouldBeEmpty()
                    world
                        .asRoot()
                        .listEventsForSeries(world.redRising)
                        .shouldBeInstanceOf<AppResult.Success<List<WorldEventSyncPayload>>>()
                        .data
                        .size shouldBe 3
                }
            }
        }

        test("revert needs contribute; a change of an event out of sight is HistoryNotFound") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val saga = world.redRising.value
                    val member = world.asMember()
                    member.applyBatch(batchOf(eventUpsert("w1", homeSeriesId = saga)))
                    member.applyBatch(EventsBatch(listOf(WorldEventOp.Delete(WorldEventId("w1")))))
                    val deletion = world.newestChange("w1")
                    deletion.op shouldBe StoryWorldOp.DELETE

                    world
                        .asMember("nocontrib")
                        .revert(deletion.id)
                        .failure()
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    member.revert(deletion.id).shouldBeInstanceOf<AppResult.Success<WorldEventChange>>()

                    world.asRoot().applyBatch(batchOf(eventUpsert("secret", homeSeriesId = saga, bookId = "hidden", positionMs = 1L)))
                    member.revert(world.newestChange("secret").id).failure().shouldBeInstanceOf<WorldEventError.HistoryNotFound>()
                }
            }
        }

        test("history hides each snapshot whose anchor the member can't see, keeping who and when") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val saga = world.redRising.value
                    val root = world.asRoot()
                    root.applyBatch(
                        batchOf(eventUpsert("w1", text = "the hidden truth", homeSeriesId = saga, bookId = "hidden", positionMs = 7L)),
                    ) shouldBe AppResult.Success(Unit)
                    root.applyBatch(
                        batchOf(eventUpsert("w1", text = "in plain sight", homeSeriesId = saga, bookId = "open", positionMs = 3L)),
                    ) shouldBe AppResult.Success(Unit)

                    val history =
                        world
                            .asMember()
                            .listHistory(WorldEventId("w1"))
                            .shouldBeInstanceOf<AppResult.Success<List<WorldEventChange>>>()
                            .data

                    history.map { it.op } shouldBe listOf(StoryWorldOp.UPDATE, StoryWorldOp.CREATE)
                    history.forEach { it.actorId shouldBe "root" }
                    val snapshots = history.flatMap { listOfNotNull(it.before, it.after) }
                    snapshots.map { it.bookId } shouldBe listOf("open")
                    snapshots.none { it.text == "the hidden truth" } shouldBe true
                    history.first().before.shouldBeNull()
                    history.last().after.shouldBeNull()
                }
            }
        }

        test("the service trims the text and the detail, and drops a blank detail") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val saga = world.redRising.value
                    world.asMember().applyBatch(
                        batchOf(
                            eventUpsert(
                                "w1",
                                type = WorldEventType.JOINS,
                                text = "  drafted  ",
                                homeSeriesId = saga,
                                subject = "darrow",
                                obj = "mars",
                                detail = "   ",
                            ),
                        ),
                    ) shouldBe AppResult.Success(Unit)

                    val saved = world.newestChange("w1").after
                    saved?.text shouldBe "drafted"
                    saved?.detail.shouldBeNull()
                }
            }
        }
    })
