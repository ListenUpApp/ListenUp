package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.db.sqldelight.Activities
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * A finish merged into an earlier read used to announce itself anyway, so one read showed as "finished"
 * twice. [FinishAnnouncementSweep] tombstones the announcements no read accounts for — each read keeps the
 * one nearest its finish — through the syncable repository, so clients converge on the deletion.
 */
class FinishAnnouncementSweepTest :
    FunSpec({

        test("a read announced twice keeps the announcement at its finish and tombstones the other") {
            withSqlDatabase {
                runTest {
                    val fixture = Fixture(this@withSqlDatabase)
                    fixture.read("r1", "u1", "b1", finishedAt = 2_000L)
                    val merged = fixture.announce("u1", "b1", occurredAt = 1_000L)
                    val kept = fixture.announce("u1", "b1", occurredAt = 2_000L)
                    val revisionBefore = fixture.row(merged).revision.shouldNotBeNull()

                    fixture.sweep.run() shouldBe 1

                    fixture.liveFinishes() shouldContainExactlyInAnyOrder listOf(kept)
                    val tombstone = fixture.row(merged)
                    tombstone.deleted_at.shouldNotBeNull()
                    tombstone.revision.shouldNotBeNull() shouldBeGreaterThan revisionBefore
                }
            }
        }

        test("two genuine reads keep both announcements") {
            withSqlDatabase {
                runTest {
                    val fixture = Fixture(this@withSqlDatabase)
                    fixture.read("r1", "u1", "b1", finishedAt = 1_000L)
                    fixture.read("r2", "u1", "b1", finishedAt = 5_000L)
                    val first = fixture.announce("u1", "b1", occurredAt = 1_000L)
                    val second = fixture.announce("u1", "b1", occurredAt = 5_000L)

                    fixture.sweep.run() shouldBe 0

                    fixture.liveFinishes() shouldContainExactlyInAnyOrder listOf(first, second)
                }
            }
        }

        test("a read re-dated backward keeps the announcement nearest its new date") {
            withSqlDatabase {
                runTest {
                    val fixture = Fixture(this@withSqlDatabase)
                    // Finished on day 10, then re-dated to day 5: the read now finishes at 5.
                    fixture.read("r1", "u1", "b1", finishedAt = 5_000L)
                    fixture.announce("u1", "b1", occurredAt = 10_000L)
                    val redated = fixture.announce("u1", "b1", occurredAt = 5_000L)

                    fixture.sweep.run() shouldBe 1

                    fixture.liveFinishes() shouldContainExactlyInAnyOrder listOf(redated)
                }
            }
        }

        test("every read keeps one announcement, and only the surplus goes") {
            withSqlDatabase {
                runTest {
                    val fixture = Fixture(this@withSqlDatabase)
                    fixture.read("r1", "u1", "b1", finishedAt = 1_000L)
                    fixture.read("r2", "u1", "b1", finishedAt = 9_000L)
                    val first = fixture.announce("u1", "b1", occurredAt = 1_000L)
                    fixture.announce("u1", "b1", occurredAt = 7_000L)
                    fixture.announce("u1", "b1", occurredAt = 8_000L)
                    val second = fixture.announce("u1", "b1", occurredAt = 9_000L)
                    // Another listener's announcement of the same book is theirs, and has its own read.
                    fixture.read("r3", "u2", "b1", finishedAt = 8_000L)
                    val theirs = fixture.announce("u2", "b1", occurredAt = 8_000L)

                    fixture.sweep.run() shouldBe 2

                    fixture.liveFinishes() shouldContainExactlyInAnyOrder listOf(first, second, theirs)
                }
            }
        }

        test("an announcement with no read at all is left alone") {
            withSqlDatabase {
                runTest {
                    val fixture = Fixture(this@withSqlDatabase)
                    val seeded = fixture.announce("u1", "b1", occurredAt = 1_000L)

                    fixture.sweep.run() shouldBe 0

                    fixture.liveFinishes() shouldContainExactlyInAnyOrder listOf(seeded)
                }
            }
        }

        test("a second run removes nothing") {
            withSqlDatabase {
                runTest {
                    val fixture = Fixture(this@withSqlDatabase)
                    fixture.read("r1", "u1", "b1", finishedAt = 3_000L)
                    fixture.announce("u1", "b1", occurredAt = 1_000L)
                    fixture.announce("u1", "b1", occurredAt = 2_000L)
                    val kept = fixture.announce("u1", "b1", occurredAt = 3_000L)

                    fixture.sweep.run() shouldBe 2
                    val revisionAfterFirst = fixture.row(kept).revision

                    fixture.sweep.run() shouldBe 0

                    fixture.liveFinishes() shouldContainExactlyInAnyOrder listOf(kept)
                    fixture.row(kept).revision shouldBe revisionAfterFirst
                }
            }
        }
    })

private class Fixture(
    private val databases: SqlTestDatabases,
) {
    private val sync = ActivitySyncRepository(db = databases.sql, bus = ChangeBus(), registry = SyncRegistry(), driver = databases.driver)
    private val reads = BookReadsRepository(db = databases.sql)
    val sweep = FinishAnnouncementSweep(sql = databases.sql, activities = sync, reads = reads)
    private val recorder = ActivityRecorder(syncRepo = sync, clock = FixedClock(Instant.fromEpochMilliseconds(CREATED_AT)))

    suspend fun read(
        id: String,
        userId: String,
        bookId: String,
        finishedAt: Long,
    ) = reads.recordRead(id = id, userId = userId, bookId = bookId, finishedAt = finishedAt, source = BookReadSource.PLAYBACK)

    /** Records a `FINISHED_BOOK` announcement and returns its id. */
    suspend fun announce(
        userId: String,
        bookId: String,
        occurredAt: Long,
    ): String {
        val before = liveFinishes().toSet()
        recorder.record(userId = userId, type = ActivityType.FINISHED_BOOK, bookId = bookId, occurredAt = occurredAt)
        return (liveFinishes().toSet() - before).single()
    }

    fun row(id: String): Activities =
        databases.sql.activitiesQueries
            .selectById(id)
            .executeAsOne()

    fun liveFinishes(): List<String> =
        databases.sql.activitiesQueries
            .pageFirst(limit = 100L)
            .executeAsList()
            .filter { it.type == ActivityType.FINISHED_BOOK }
            .map { it.id }

    private companion object {
        const val CREATED_AT = 50_000L
    }
}
