package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val ACTOR = UserId("u1")

/** Delete, merge and revert: each is a forward write that records its own history entry. */
class EntityRepositoryWritesTest :
    FunSpec({
        test("delete records DELETE, and reverting it brings the entity back as a forward write") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Sevro"), ACTOR)
                    repo.deleteEntity(EntityId("e1"), ACTOR) shouldBe AppResult.Success(Unit)
                    val deletion = repo.listHistory(EntityId("e1")).first()
                    deletion.op shouldBe StoryWorldOp.DELETE

                    val reverted =
                        repo
                            .revert(
                                deletion.id,
                                ACTOR,
                                allowMergeRevert = true,
                            ).shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                            .data
                    reverted.op shouldBe StoryWorldOp.REVERT
                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()
                    repo.findById(EntityId("e1")).shouldNotBeNull().name shouldBe "Sevro"
                }
            }
        }

        test("reverting a CREATE deletes, and that revert can itself be reverted") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1"), ACTOR)
                    val creation = repo.listHistory(EntityId("e1")).single()
                    val undoCreate =
                        repo
                            .revert(
                                creation.id,
                                ACTOR,
                                allowMergeRevert = true,
                            ).shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                            .data
                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()

                    repo.revert(undoCreate.id, ACTOR, allowMergeRevert = true).shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()
                }
            }
        }

        test("undoing the undo of a delete deletes the entity again") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1"), ACTOR)
                    repo.deleteEntity(EntityId("e1"), ACTOR) shouldBe AppResult.Success(Unit)
                    val undoDelete =
                        repo
                            .revert(repo.listHistory(EntityId("e1")).first().id, ACTOR, allowMergeRevert = false)
                            .shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                            .data

                    repo.revert(undoDelete.id, ACTOR, allowMergeRevert = false).shouldBeInstanceOf<AppResult.Success<EntityChange>>()

                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                }
            }
        }

        test("undoing a book re-add's revival deletes the entity again") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1"), ACTOR)
                    repo.softDeleteAllForBook("b1") shouldBe 1
                    repo.reviveAllForBooks(listOf("b1")) shouldBe 1
                    val revival = repo.listHistory(EntityId("e1")).first()
                    revival.op shouldBe StoryWorldOp.REVERT

                    repo.revert(revival.id, ACTOR, allowMergeRevert = false).shouldBeInstanceOf<AppResult.Success<EntityChange>>()

                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                }
            }
        }

        test("reverting an edit restores the earlier name") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Mustang"), ACTOR)
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Virginia"), ACTOR)
                    val edit = repo.listHistory(EntityId("e1")).first()

                    repo.revert(edit.id, ACTOR, allowMergeRevert = false)

                    repo.findById(EntityId("e1")).shouldNotBeNull().name shouldBe "Mustang"
                }
            }
        }

        test("merge moves the source's children to the target, tombstones the source and records MERGE") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("a", kind = EntityKind.GROUP, homeBookId = "b1"), ACTOR)
                    repo.upsertEntity(entityPayload("b", kind = EntityKind.GROUP, homeBookId = "b1"), ACTOR)
                    repo.upsertEntity(entityPayload("kid", kind = EntityKind.GROUP, homeBookId = "b1", parentId = "a"), ACTOR)

                    repo.mergeEntities(EntityId("a"), EntityId("b"), ACTOR).shouldBeInstanceOf<AppResult.Success<*>>()

                    repo
                        .findById(EntityId("a"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                    repo.findById(EntityId("kid")).shouldNotBeNull().parentId shouldBe "b"
                    repo.listHistory(EntityId("a")).first().op shouldBe StoryWorldOp.MERGE
                }
            }
        }

        test("merge refuses another kind, and a target that sits beneath the source") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("place", kind = EntityKind.LOCATION, homeBookId = "b1"), ACTOR)
                    repo.upsertEntity(entityPayload("inner", kind = EntityKind.LOCATION, homeBookId = "b1", parentId = "place"), ACTOR)
                    repo.upsertEntity(entityPayload("person", kind = EntityKind.CHARACTER, homeBookId = "b1"), ACTOR)

                    repo
                        .mergeEntities(EntityId("person"), EntityId("place"), ACTOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.KindMismatchOnMerge>()
                    repo
                        .mergeEntities(EntityId("place"), EntityId("inner"), ACTOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.CycleDetected>()
                }
            }
        }

        test("a content revert is decided against the row as it stands inside the revert's transaction") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Mustang"), ACTOR)
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Virginia"), ACTOR)
                    val rename = repo.listHistory(EntityId("e1")).first()
                    // Another contributor's delete lands between the service's read and the revert's transaction.
                    repo.deleteEntity(EntityId("e1"), UserId("other"))

                    val reverted =
                        repo
                            .revert(rename.id, ACTOR, allowMergeRevert = false)
                            .shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                            .data
                    // The REVERT's `before` is the tombstone the delete left, read inside the transaction.
                    reverted.before
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                    repo.findById(EntityId("e1")).shouldNotBeNull().let { entity ->
                        entity.deletedAt.shouldBeNull()
                        entity.name shouldBe "Mustang"
                    }
                }
            }
        }

        test("without merge-revert a caller still reverts a create and a delete, but not a merge") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1"), ACTOR)
                    val creation = repo.listHistory(EntityId("e1")).single()
                    repo.revert(creation.id, ACTOR, allowMergeRevert = false).shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                    val undoCreate = repo.listHistory(EntityId("e1")).first()
                    repo.revert(undoCreate.id, ACTOR, allowMergeRevert = false).shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                    repo.deleteEntity(EntityId("e1"), ACTOR)
                    val deletion = repo.listHistory(EntityId("e1")).first()
                    repo.revert(deletion.id, ACTOR, allowMergeRevert = false).shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()

                    repo.upsertEntity(entityPayload("e2", homeBookId = "b1"), ACTOR)
                    repo.mergeEntities(EntityId("e1"), EntityId("e2"), ACTOR)
                    val merge = repo.listHistory(EntityId("e1")).first()
                    merge.op shouldBe StoryWorldOp.MERGE
                    repo
                        .revert(merge.id, ACTOR, allowMergeRevert = false)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                    repo.revert(merge.id, ACTOR, allowMergeRevert = true).shouldBeInstanceOf<AppResult.Success<EntityChange>>()
                    repo
                        .findById(EntityId("e1"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()
                }
            }
        }

        test("revert of an unknown change is HistoryNotFound") {
            withSqlDatabase {
                runTest {
                    entityRepository()
                        .revert(
                            com.calypsan.listenup.core
                                .StoryWorldHistoryId("nope"),
                            ACTOR,
                            allowMergeRevert = true,
                        ).shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.HistoryNotFound>()
                }
            }
        }
    })
