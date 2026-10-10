package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.testing.eventUpsert
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.record
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.testing.worldEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private val ACTOR = UserId("u1")

/** The `world_events` domain is access-gated by home and anchor on pull, digest, targeted match and firehose. */
class WorldEventAccessTest :
    FunSpec({
        test("the filtered pull, digest and book-id match serve only events whose home and anchor the member can see") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                val repo = worldEventRepository()
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open", "hidden")
                    val closed = seedSeriesWithBooks("Closed", "hidden2")
                    sql.seedTestBook("solo")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    repo.record(eventUpsert("unanchored", homeSeriesId = mixed.value))
                    repo.record(eventUpsert("at-open", homeSeriesId = mixed.value, bookId = "open", positionMs = 1L))
                    repo.record(eventUpsert("at-hidden", homeSeriesId = mixed.value, bookId = "hidden", positionMs = 1L))
                    repo.record(eventUpsert("closed-world", homeSeriesId = closed.value))
                    repo.record(eventUpsert("solo-world", homeBookId = "solo"))
                    val filter = accessFilterFor("world_events", "viewer", UserRole.MEMBER) { BookAccessPolicy(sql, driver) }

                    repo
                        .pullSince(userId = "viewer", cursor = 0L, limit = 50, extraWhere = filter)
                        .items
                        .map { it.id }
                        .toSet() shouldBe setOf("unanchored", "at-open")
                    repo.digest(userId = "viewer", cursor = Long.MAX_VALUE, extraWhere = filter).count shouldBe 2
                    repo
                        .pullByIds(userId = "viewer", matchColumn = "book_id", matchValues = listOf("open", "hidden"), extraWhere = filter)
                        .items
                        .map { it.id }
                        .toSet() shouldBe setOf("unanchored", "at-open")
                }
            }
        }

        test("world_events is a per-row gated domain that supports the book-id match") {
            ("world_events" in perRowAccessGatedSyncDomains) shouldBe true
            ("world_events" in BOOK_ID_MATCH_DOMAINS) shouldBe true
        }

        test("the firehose withholds an event whose home or anchor the member can't see, and passes tombstones") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                val repo = worldEventRepository()
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open", "hidden")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    val policy = BookAccessPolicy(sql, driver)

                    fun created(anchor: String?) =
                        BusEvent(
                            repo = repo,
                            event =
                                SyncEvent.Created(
                                    id = "w-$anchor",
                                    revision = 1L,
                                    occurredAt = 0L,
                                    payload =
                                        WorldEventSyncPayload(
                                            id = "w-$anchor",
                                            homeSeriesId = mixed.value,
                                            bookId = anchor,
                                            positionMs = anchor?.let { 1L },
                                            type = WorldEventType.NOTE,
                                            text = "x",
                                            revision = 1L,
                                            updatedAt = 0L,
                                            createdAt = 0L,
                                        ),
                                ),
                        )

                    firehoseGateReason(created("hidden"), "viewer", UserRole.MEMBER) { policy }.shouldNotBeNull()
                    firehoseGateReason(created("open"), "viewer", UserRole.MEMBER) { policy }.shouldBeNull()
                    firehoseGateReason(created(null), "viewer", UserRole.MEMBER) { policy }.shouldBeNull()
                    firehoseGateReason(created("hidden"), "admin", UserRole.ADMIN) { policy }.shouldBeNull()
                    firehoseGateReason(
                        BusEvent(repo = repo, event = SyncEvent.Deleted(id = "x", revision = 2L, occurredAt = 0L, clientOpId = null)),
                        "viewer",
                        UserRole.MEMBER,
                    ) { policy }.shouldBeNull()
                }
            }
        }

        test("a visible tombstone reaches the member stripped of everything but its identity") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("viewer")
                val repo = worldEventRepository()
                runTest {
                    val mixed = seedSeriesWithBooks("Mixed", "open")
                    makeBookAccessible(sql, driver, bookId = "open", viewerId = "viewer")
                    repo.record(eventUpsert("gone", text = "Eo dies", homeSeriesId = mixed.value, bookId = "open", positionMs = 5L))
                    repo.applyBatch(listOf(WorldEventOp.Delete(WorldEventId("gone"))), ACTOR)
                    val filter = accessFilterFor("world_events", "viewer", UserRole.MEMBER) { BookAccessPolicy(sql, driver) }

                    val tombstone = repo.pullSince(userId = "viewer", cursor = 0L, limit = 50, extraWhere = filter).items.single()
                    tombstone.deletedAt.shouldNotBeNull()
                    tombstone.text shouldBe ""
                    tombstone.type shouldBe WorldEventType.UNKNOWN
                    tombstone.homeSeriesId.shouldBeNull()
                    tombstone.bookId.shouldBeNull()
                    tombstone.positionMs.shouldBeNull()
                    tombstone.mentionIds.shouldBeEmpty()
                    tombstone.createdBy.shouldBeNull()
                }
            }
        }
    })
