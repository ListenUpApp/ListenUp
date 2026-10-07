package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun as_(
    userId: String,
    role: UserRole,
) = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("s-$userId"), role) }

private fun upsert(
    id: String,
    homeBookId: String? = null,
    homeSeriesId: String? = null,
    name: String = id,
    kind: EntityKind = EntityKind.CHARACTER,
) = EntityUpsert(
    id = EntityId(id),
    kind = kind,
    name = name,
    homeBookId = homeBookId?.let(::BookId),
    homeSeriesId = homeSeriesId?.let(::SeriesId),
)

/** The world every test here uses. "open" is visible to the three members; "hidden" and "hidden2" only to admins. */
private class World(
    val service: EntityServiceImpl,
    val redRising: SeriesId,
    val sealed: SeriesId,
) {
    fun asMember(userId: String) = service.copyWith(as_(userId, UserRole.MEMBER))

    fun asRoot() = service.copyWith(as_("root", UserRole.ROOT))

    /** The newest [op] in [entityId]'s history, read as root. */
    suspend fun changeOf(
        entityId: String,
        op: StoryWorldOp,
    ): StoryWorldHistoryId =
        asRoot()
            .listHistory(EntityId(entityId))
            .shouldBeInstanceOf<AppResult.Success<List<EntityChange>>>()
            .data
            .first { it.op == op }
            .id
}

private suspend fun SqlTestDatabases.storyWorld(): World {
    sql.seedTestLibraryAndFolder()
    listOf("member", "nocontrib", "curator").forEach { sql.seedTestUser(it) }
    sql.seedTestUser("root", UserRoleColumn.ROOT)
    val redRising = seedSeriesWithBooks("Red Rising", "open", "hidden")
    val sealed = seedSeriesWithBooks("Sealed", "hidden2")
    listOf("member", "nocontrib", "curator").forEach { makeBookAccessible(sql, driver, bookId = "open", viewerId = it) }
    sql.usersQueries.updateStoryWorldPermissions(can_contribute_story_world = 0L, can_curate_story_world = 0L, id = "nocontrib")
    sql.usersQueries.updateStoryWorldPermissions(can_contribute_story_world = 1L, can_curate_story_world = 1L, id = "curator")
    val service = EntityServiceImpl(entityRepository(), UserPermissionPolicy(sql), BookAccessPolicy(sql, driver))
    return World(service, redRising, sealed)
}

/** [EntityServiceImpl]'s permission × visibility matrix and its input rules. */
class EntityServiceImplTest :
    FunSpec({
        test("contribute: members may create and edit by default; a member without the flag may not") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    world
                        .asMember("member")
                        .upsertEntity(upsert("e1", homeBookId = "open"))
                        .shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                    world
                        .asMember("nocontrib")
                        .upsertEntity(upsert("e2", homeBookId = "open"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("curate: delete and merge need the curator flag; admins hold it implicitly") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val member = world.asMember("member")
                    member.upsertEntity(upsert("a", homeBookId = "open"))
                    member.upsertEntity(upsert("b", homeBookId = "open"))

                    member
                        .deleteEntity(EntityId("a"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    member
                        .mergeEntities(EntityId("a"), EntityId("b"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    world
                        .asMember("curator")
                        .mergeEntities(EntityId("a"), EntityId("b"))
                        .shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                    world.asRoot().deleteEntity(EntityId("b")) shouldBe AppResult.Success(Unit)
                }
            }
        }

        test("visibility: a member, even a curator, can't write to, read, or learn about a world they can't see") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    world.asRoot().upsertEntity(upsert("secret", homeBookId = "hidden"))
                    val member = world.asMember("member")

                    member
                        .upsertEntity(upsert("x", homeBookId = "hidden"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.NotFound>()
                    member
                        .upsertEntity(upsert("secret", homeBookId = "hidden"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.NotFound>()
                    member
                        .listHistory(EntityId("secret"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.NotFound>()
                    member
                        .listEntitiesForBook(BookId("hidden"))
                        .shouldBeInstanceOf<AppResult.Success<List<EntitySyncPayload>>>()
                        .data
                        .shouldBeEmpty()

                    val curator = world.asMember("curator")
                    world.asRoot().upsertEntity(upsert("secret2", homeBookId = "hidden"))
                    curator
                        .deleteEntity(EntityId("secret"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.NotFound>()
                    curator
                        .mergeEntities(EntityId("secret"), EntityId("secret2"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.NotFound>()
                    val secretChange = (world.asRoot().listHistory(EntityId("secret")) as AppResult.Success).data.single()
                    curator
                        .revert(secretChange.id)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.HistoryNotFound>()
                }
            }
        }

        test("reusing a hidden entity's id under a visible home answers NotFound, not a home-change refusal") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    world.asRoot().upsertEntity(upsert("secret", homeBookId = "hidden"))

                    world
                        .asMember("member")
                        .upsertEntity(upsert("secret", homeBookId = "open"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.NotFound>()
                }
            }
        }

        test("naming a hidden entity as a parent answers exactly as naming an id that doesn't exist") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    world.asRoot().upsertEntity(upsert("secret", homeBookId = "hidden", kind = EntityKind.CHARACTER))
                    val member = world.asMember("member")

                    val hidden =
                        member
                            .upsertEntity(upsert("x", homeBookId = "open", kind = EntityKind.LOCATION).copy(parentId = EntityId("secret")))
                            .shouldBeInstanceOf<AppResult.Failure>()
                            .error
                    val absent =
                        member
                            .upsertEntity(upsert("x", homeBookId = "open", kind = EntityKind.LOCATION).copy(parentId = EntityId("nobody")))
                            .shouldBeInstanceOf<AppResult.Failure>()
                            .error

                    hidden.shouldBeInstanceOf<EntityError.InvalidParent>()
                    hidden shouldBe absent
                }
            }
        }

        test("a series-homed entity is visible once one of the series' books is") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    world.asRoot().upsertEntity(upsert("house", homeSeriesId = world.redRising.value, kind = EntityKind.GROUP))
                    world.asRoot().upsertEntity(upsert("closed", homeSeriesId = world.sealed.value, kind = EntityKind.GROUP))
                    val member = world.asMember("member")

                    member
                        .listEntitiesForSeries(world.redRising)
                        .shouldBeInstanceOf<AppResult.Success<List<EntitySyncPayload>>>()
                        .data
                        .map { it.id } shouldBe listOf("house")
                    member
                        .listEntitiesForSeries(world.sealed)
                        .shouldBeInstanceOf<AppResult.Success<List<EntitySyncPayload>>>()
                        .data
                        .shouldBeEmpty()
                }
            }
        }

        test("input rules: exactly one home, a kind on create, a fixed home, a 200-character name, a 60-character descriptor") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val member = world.asMember("member")
                    member
                        .upsertEntity(upsert("x"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                    member
                        .upsertEntity(upsert("x", homeBookId = "open", kind = EntityKind.UNKNOWN))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                    member
                        .upsertEntity(upsert("x", homeBookId = "open", name = "n".repeat(201)))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                        .field shouldBe "name"
                    member
                        .upsertEntity(upsert("x", homeBookId = "open", name = "n".repeat(200)))
                        .shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                    member
                        .upsertEntity(upsert("x", homeBookId = "open").copy(descriptor = "d".repeat(61)))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                    member.upsertEntity(upsert("y", homeBookId = "open"))
                    member
                        .upsertEntity(upsert("y", homeSeriesId = world.redRising.value))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("an older client's UNKNOWN kind on an edit keeps the stored kind") {
            withSqlDatabase {
                runTest {
                    val member = storyWorld().asMember("member")
                    member.upsertEntity(upsert("g", homeBookId = "open", kind = EntityKind.GROUP))
                    member
                        .upsertEntity(upsert("g", homeBookId = "open", kind = EntityKind.UNKNOWN, name = "Gold"))
                        .shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                        .data.kind shouldBe EntityKind.GROUP
                }
            }
        }

        test("revert needs contribute and a visible entity") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val member = world.asMember("member")
                    member.upsertEntity(upsert("e", homeBookId = "open"))
                    member.upsertEntity(upsert("e", homeBookId = "open", name = "Eo"))
                    val rename = world.changeOf("e", StoryWorldOp.UPDATE)

                    world
                        .asMember("nocontrib")
                        .revert(rename)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    member.revert(rename).shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("a contributor reverts content edits; reverting a create, delete or merge needs curate") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val root = world.asRoot()
                    listOf("u", "c", "d", "m", "t").forEach { root.upsertEntity(upsert(it, homeBookId = "open")) }
                    root.upsertEntity(upsert("u", homeBookId = "open", name = "Renamed"))
                    root.deleteEntity(EntityId("d"))
                    root.mergeEntities(EntityId("m"), EntityId("t"))
                    val member = world.asMember("member")

                    member.revert(world.changeOf("u", StoryWorldOp.UPDATE)).shouldBeInstanceOf<AppResult.Success<*>>()
                    listOf(
                        world.changeOf("c", StoryWorldOp.CREATE),
                        world.changeOf("d", StoryWorldOp.DELETE),
                        world.changeOf("m", StoryWorldOp.MERGE),
                    ).forEach { structural ->
                        member
                            .revert(structural)
                            .shouldBeInstanceOf<AppResult.Failure>()
                            .error
                            .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    }

                    val curator = world.asMember("curator")
                    curator.revert(world.changeOf("u", StoryWorldOp.UPDATE)).shouldBeInstanceOf<AppResult.Success<*>>()
                    curator.revert(world.changeOf("c", StoryWorldOp.CREATE)).shouldBeInstanceOf<AppResult.Success<*>>()
                    curator.revert(world.changeOf("d", StoryWorldOp.DELETE)).shouldBeInstanceOf<AppResult.Success<*>>()
                    curator.revert(world.changeOf("m", StoryWorldOp.MERGE)).shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("a contributor can't revive a deleted entry by reverting one of its content edits") {
            withSqlDatabase {
                runTest {
                    val world = storyWorld()
                    val root = world.asRoot()
                    root.upsertEntity(upsert("d", homeBookId = "open"))
                    root.upsertEntity(upsert("d", homeBookId = "open", name = "Renamed"))
                    root.deleteEntity(EntityId("d"))

                    world
                        .asMember("member")
                        .revert(world.changeOf("d", StoryWorldOp.UPDATE))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })
