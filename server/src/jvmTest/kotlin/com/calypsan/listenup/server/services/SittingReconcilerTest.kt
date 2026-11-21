package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.sync.SittingStart
import com.calypsan.listenup.api.sync.SittingSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private const val MINUTE = 60_000L
private const val SECOND = 1_000L

/** Far enough ahead of every fixture that nothing is clamped unless a test means it to be. */
private const val NOW = 10_000L * MINUTE

/**
 * [SittingReconciler] turns closed listening spans into the `sittings` rows the Activity feed shows: one row
 * per person's unbroken time with one book, by the shared rule, real listens only, rewritten in place.
 */
class SittingReconcilerTest :
    FunSpec({

        test("spans an hour or less apart are one sitting, from the first start to the last end") {
            withSittings {
                listen("e1", from = 0, to = 20)
                listen("e2", from = 30, to = 50) // the kettle
                listen("e3", from = 100, to = 110) // lunch at the desk, 50 minutes

                reconcile()

                val sitting = sittings().single()
                sitting.startedAt shouldBe 0L
                sitting.endedAt shouldBe 110 * MINUTE
                sitting.listenedMs shouldBe 50 * MINUTE
            }
        }

        test("an idle stretch over an hour splits the listening into two sittings") {
            withSittings {
                listen("e1", from = 0, to = 20)
                listen("e2", from = 81, to = 90) // 61 minutes later

                reconcile()

                sittings().map { it.startedAt to it.endedAt } shouldBe
                    listOf(0L to 20 * MINUTE, 81 * MINUTE to 90 * MINUTE)
            }
        }

        test("another book played in between does not split a sitting") {
            withSittings {
                listen("a1", from = 0, to = 59)
                listen("b1", from = 59, to = 70, bookId = OTHER_BOOK)
                listen("a2", from = 70, to = 90)

                reconcile()
                reconcile(OTHER_BOOK)

                sittings().map { it.startedAt to it.endedAt } shouldBe listOf(0L to 90 * MINUTE)
                sittings(OTHER_BOOK) shouldHaveSize 1
            }
        }

        test("time two devices played at once is counted once") {
            withSittings {
                listen("phone", from = 0, to = 60)
                listen("tablet", from = 30, to = 70)

                reconcile()

                sittings().single().listenedMs shouldBe 70 * MINUTE
            }
        }

        test("under a minute of listening is not a sitting; several short fragments that add up to one are") {
            withSittings {
                listenMs("tap", fromMs = 0, toMs = 59 * SECOND)
                reconcile()
                sittings().shouldBeEmpty()

                (1..5).forEach { i -> listenMs("frag-$i", fromMs = i * MINUTE, toMs = i * MINUTE + 20 * SECOND) }
                reconcile()

                sittings().single().listenedMs shouldBe 59 * SECOND + 100 * SECOND
            }
        }

        test("a span that ends before it starts adds nothing, and alone makes no sitting") {
            withSittings {
                listenMs("inverted", fromMs = 30 * MINUTE, toMs = 0)
                reconcile()
                sittings().shouldBeEmpty()

                listen("real", from = 0, to = 10)
                reconcile()

                sittings().single().listenedMs shouldBe 10 * MINUTE
            }
        }

        test("a sitting that grows keeps its id and gets a newer revision") {
            withSittings {
                listen("e1", from = 0, to = 10)
                reconcile()
                val before = sittings().single()

                listen("e2", from = 15, to = 40)
                reconcile()

                val after = sittings().single()
                after.id shouldBe before.id
                after.endedAt shouldBe 40 * MINUTE
                after.listenedMs shouldBe 35 * MINUTE
                (after.revision > before.revision) shouldBe true
            }
        }

        test("rebuilding from unchanged evidence writes nothing") {
            withSittings {
                listen("e1", from = 0, to = 10)
                listen("e2", from = 200, to = 220)
                reconcile() shouldBe 2
                val before = sittings()

                reconcile() shouldBe 0

                sittings() shouldBe before
            }
        }

        test("late evidence that bridges two sittings leaves one, keeping the earlier one's id") {
            withSittings {
                listen("e1", from = 0, to = 10)
                listen("e2", from = 100, to = 110)
                reconcile()
                val (earlier, later) = sittings()

                listen("offline", from = 50, to = 60) // synced hours later, fills the gap
                reconcile()

                val merged = sittings().single()
                merged.id shouldBe earlier.id
                merged.endedAt shouldBe 110 * MINUTE
                tombstone(later.id) shouldNotBe null
            }
        }

        test("a sitting whose evidence is deleted below a real listen is tombstoned") {
            withSittings {
                listen("e1", from = 0, to = 10)
                reconcile()
                val sitting = sittings().single()

                sql.listeningEventsQueries.softDeleteById(revision = 99, updated_at = 1, deleted_at = 1, client_op_id = null, id = "e1")
                reconcile()

                sittings().shouldBeEmpty()
                tombstone(sitting.id) shouldNotBe null
            }
        }

        test("a sitting carries the start and the finish announced inside it") {
            withSittings {
                listen("e1", from = 0, to = 30)
                announce(ActivityType.STARTED_BOOK, atMinute = 0, isReread = true)
                announce(ActivityType.FINISHED_BOOK, atMinute = 31)

                reconcile()

                val sitting = sittings().single()
                sitting.beganRead shouldBe SittingStart.REREAD
                sitting.finishedRead shouldBe true
            }
        }

        test("a first start opened a little before the listening began still belongs to the sitting") {
            withSittings {
                announce(ActivityType.STARTED_BOOK, atMinute = 0)
                listen("e1", from = 20, to = 40)
                listen("e2", from = 300, to = 320) // a later evening

                reconcile()

                sittings().map { it.beganRead } shouldBe listOf(SittingStart.FIRST, SittingStart.NONE)
            }
        }

        test("a sitting that ends after the server's clock is dated now, not in the future") {
            withSittings(nowMs = 30 * MINUTE) {
                listen("ahead", from = 0, to = 45)

                reconcile()

                sittings().single().endedAt shouldBe 30 * MINUTE
            }
        }

        test("rebuilding a user rebuilds every book of theirs") {
            withSittings {
                listen("a", from = 0, to = 10)
                listen("b", from = 0, to = 10, bookId = OTHER_BOOK)

                reconciler.rebuildUser(USER) shouldBe 2

                sittings() shouldHaveSize 1
                sittings(OTHER_BOOK) shouldHaveSize 1
                reconciler.rebuildUser(USER) shouldBe 0
            }
        }
    })

private const val USER = "darlene"
private const val BOOK = "red-rising"
private const val OTHER_BOOK = "golden-son"

/** A reconciler over a fresh database, with [USER] and both books seeded, and helpers to seed evidence. */
private class SittingsFixture(
    databases: SqlTestDatabases,
    nowMs: Long,
) {
    val sql: ListenUpDatabase = databases.sql
    val repository = SittingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = databases.driver)
    val reconciler =
        SittingReconciler(db = sql, sittings = repository, bus = ChangeBus(), clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)))

    fun listen(
        id: String,
        from: Long,
        to: Long,
        bookId: String = BOOK,
    ) = listenMs(id = id, fromMs = from * MINUTE, toMs = to * MINUTE, bookId = bookId)

    fun listenMs(
        id: String,
        fromMs: Long,
        toMs: Long,
        bookId: String = BOOK,
    ) {
        sql.listeningEventsQueries.insert(
            id = id,
            user_id = USER,
            book_id = bookId,
            start_position_ms = 0L,
            end_position_ms = 0L,
            started_at = fromMs,
            ended_at = toMs,
            playback_speed = 1.0,
            tz = "UTC",
            device_label = null,
            revision = 1L,
            created_at = 1L,
            updated_at = 1L,
            deleted_at = null,
            client_op_id = null,
        )
    }

    fun announce(
        type: String,
        atMinute: Long,
        isReread: Boolean = false,
    ) {
        sql.activitiesQueries.insert(
            id = "$type-$atMinute",
            user_id = USER,
            type = type,
            created_at = atMinute * MINUTE,
            occurred_at = atMinute * MINUTE,
            book_id = BOOK,
            is_reread = if (isReread) 1L else 0L,
            duration_ms = 0L,
            milestone_value = 0L,
            milestone_unit = null,
            shelf_id = null,
            shelf_name = null,
        )
    }

    suspend fun reconcile(bookId: String = BOOK): Int = reconciler.rebuildBook(USER, bookId)

    suspend fun sittings(bookId: String = BOOK): List<SittingSyncPayload> = repository.liveFor(USER, bookId)

    fun tombstone(id: String): Long? =
        sql.sittingsQueries
            .selectById(id)
            .executeAsOne()
            .deleted_at
}

private fun withSittings(
    nowMs: Long = NOW,
    block: suspend SittingsFixture.() -> Unit,
) = withSqlDatabase {
    sql.seedTestLibraryAndFolder()
    sql.seedTestUser(USER)
    sql.seedTestBook(BOOK)
    sql.seedTestBook(OTHER_BOOK)
    runTest { SittingsFixture(this@withSqlDatabase, nowMs).block() }
}
