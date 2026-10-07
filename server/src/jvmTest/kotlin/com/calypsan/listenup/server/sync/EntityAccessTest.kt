package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.entityRepository
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private val ACTOR = UserId("u1")

/** The `entities` domain is access-gated by home on pull, digest, targeted match and the firehose. */
class EntityAccessTest :
    FunSpec({
        test("the filtered pull, digest and book-id match serve only entities whose home the member can see") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                val repo = entityRepository()
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open", "hidden")
                    val closed = seedSeriesWithBooks("Closed", "hidden2")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    repo.upsertEntity(entityPayload("on-open", homeBookId = "open"), ACTOR)
                    repo.upsertEntity(entityPayload("on-hidden", homeBookId = "hidden"), ACTOR)
                    repo.upsertEntity(entityPayload("on-mixed", homeSeriesId = mixed.value), ACTOR)
                    repo.upsertEntity(entityPayload("on-closed", homeSeriesId = closed.value), ACTOR)
                    val filter = accessFilterFor("entities", "viewer", UserRole.MEMBER) { BookAccessPolicy(sql, driver) }

                    repo
                        .pullSince(userId = "viewer", cursor = 0L, limit = 50, extraWhere = filter)
                        .items
                        .map { it.id }
                        .toSet() shouldBe setOf("on-open", "on-mixed")
                    repo.digest(userId = "viewer", cursor = Long.MAX_VALUE, extraWhere = filter).count shouldBe 2
                    repo
                        .pullByIds(userId = "viewer", matchColumn = "book_id", matchValues = listOf("open", "hidden"), extraWhere = filter)
                        .items
                        .map { it.id }
                        .toSet() shouldBe setOf("on-open", "on-mixed")
                }
            }
        }

        test("entities is a per-row gated domain that supports the book-id match") {
            ("entities" in perRowAccessGatedSyncDomains) shouldBe true
            ("entities" in BOOK_ID_MATCH_DOMAINS) shouldBe true
        }

        test("the firehose withholds an entity whose home the member can't see, and passes tombstones") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                val repo = entityRepository()
                runTest {
                    seedSeriesWithBooks("Mixed", "open", "hidden")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    val policy = BookAccessPolicy(sql, driver)
                    fun created(homeBookId: String) =
                        BusEvent(
                            repo = repo,
                            event =
                                SyncEvent.Created(
                                    id = "e-$homeBookId",
                                    revision = 1L,
                                    occurredAt = 0L,
                                    payload = entityPayload("e-$homeBookId", homeBookId = homeBookId),
                                ),
                        )

                    firehoseGateReason(created("hidden"), "viewer", UserRole.MEMBER) { policy }.shouldNotBeNull()
                    firehoseGateReason(created("open"), "viewer", UserRole.MEMBER) { policy }.shouldBeNull()
                    firehoseGateReason(created("hidden"), "admin", UserRole.ADMIN) { policy }.shouldBeNull()
                    firehoseGateReason(
                        BusEvent(repo = repo, event = SyncEvent.Deleted(id = "x", revision = 2L, occurredAt = 0L, clientOpId = null)),
                        "viewer",
                        UserRole.MEMBER,
                    ) { policy }.shouldBeNull()
                }
            }
        }

        test("a visible tombstone reaches the member stripped of its name, home and authors") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                val repo = entityRepository()
                runTest {
                    seedSeriesWithBooks("Mixed", "open")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    repo.upsertEntity(entityPayload("gone", homeBookId = "open", name = "Eo"), ACTOR)
                    repo.deleteEntity(EntityId("gone"), ACTOR)
                    val filter = accessFilterFor("entities", "viewer", UserRole.MEMBER) { BookAccessPolicy(sql, driver) }

                    val tombstone = repo.pullSince(userId = "viewer", cursor = 0L, limit = 50, extraWhere = filter).items.single()
                    tombstone.deletedAt.shouldNotBeNull()
                    tombstone.name shouldBe ""
                    tombstone.homeBookId.shouldBeNull()
                    tombstone.homeSeriesId.shouldBeNull()
                    tombstone.createdBy.shouldBeNull()
                    tombstone.updatedBy.shouldBeNull()
                }
            }
        }
    })
