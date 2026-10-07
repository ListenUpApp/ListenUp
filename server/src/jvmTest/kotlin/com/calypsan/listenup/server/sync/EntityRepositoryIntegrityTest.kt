package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private val CREATOR = UserId("u1")
private val EDITOR = UserId("u2")

/**
 * The integrity rules [EntityRepository.upsertEntity] decides against the stored row inside the write's
 * own transaction: a fixed home, no UNKNOWN create, the stored kind under an UNKNOWN edit, server-owned
 * authorship, a carried-over image, and no revival of a tombstone.
 */
class EntityRepositoryIntegrityTest :
    FunSpec({
        test("an edit that names another home is a ValidationError, and the row keeps its home") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestBook("b2")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1"), CREATOR)

                    repo
                        .upsertEntity(entityPayload("e1", homeBookId = "b2"), CREATOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                    repo
                        .upsertEntity(entityPayload("e1", homeSeriesId = "s1"), CREATOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                    repo.findById(EntityId("e1")).shouldNotBeNull().homeBookId shouldBe "b1"
                    repo.listHistory(EntityId("e1")) shouldHaveSize 1
                }
            }
        }

        test("UNKNOWN is refused on create and keeps the stored kind on an edit") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo
                        .upsertEntity(entityPayload("new", kind = EntityKind.UNKNOWN, homeBookId = "b1"), CREATOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<ValidationError>()
                    repo.findById(EntityId("new")) shouldBe null

                    repo.upsertEntity(entityPayload("g", kind = EntityKind.GROUP, homeBookId = "b1"), CREATOR)
                    repo
                        .upsertEntity(entityPayload("g", kind = EntityKind.UNKNOWN, homeBookId = "b1", name = "Gold"), CREATOR)
                        .shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                        .data
                        .kind shouldBe EntityKind.GROUP
                }
            }
        }

        test("a client-supplied createdBy is ignored: the actor creates, and every write's actor is updatedBy") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    val created =
                        repo
                            .upsertEntity(
                                entityPayload("e1", homeBookId = "b1").copy(createdBy = "mallory", updatedBy = "mallory"),
                                CREATOR,
                            ).shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                            .data
                    created.createdBy shouldBe CREATOR.value
                    created.updatedBy shouldBe CREATOR.value

                    val edited =
                        repo
                            .upsertEntity(
                                entityPayload("e1", homeBookId = "b1", name = "Reaper").copy(createdBy = "mallory", updatedBy = "mallory"),
                                EDITOR,
                            ).shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                            .data
                    edited.createdBy shouldBe CREATOR.value
                    edited.updatedBy shouldBe EDITOR.value
                }
            }
        }

        test("an edit carries the stored image over") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1").copy(imageRef = "portrait"), CREATOR)

                    repo
                        .upsertEntity(entityPayload("e1", homeBookId = "b1", name = "Reaper").copy(imageRef = null), EDITOR)
                        .shouldBeInstanceOf<AppResult.Success<EntitySyncPayload>>()
                        .data
                        .imageRef shouldBe "portrait"
                }
            }
        }

        test("an edit to a deleted entity is NotFound and does not revive it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                val repo = entityRepository()
                runTest {
                    repo.upsertEntity(entityPayload("e1", homeBookId = "b1"), CREATOR)
                    repo.deleteEntity(EntityId("e1"), CREATOR)

                    repo
                        .upsertEntity(entityPayload("e1", homeBookId = "b1", name = "late offline edit"), EDITOR)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.NotFound>()
                    repo.findById(EntityId("e1")).shouldNotBeNull().deletedAt.shouldNotBeNull()
                    repo.listHistory(EntityId("e1")) shouldHaveSize 2
                }
            }
        }
    })
