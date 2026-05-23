@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.sync.ActiveSessionSyncPayload
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ActiveSessionCleanupTaskTest :
    FunSpec({

        fun makeRepo(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            clock: kotlin.time.Clock = kotlin.time.Clock.System,
        ): ActiveSessionRepository =
            ActiveSessionRepository(db = db, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)

        fun session(
            sessionId: String,
            bookId: String,
            startedAt: Long,
        ) = ActiveSessionSyncPayload(
            sessionId = sessionId,
            bookId = bookId,
            startedAt = startedAt,
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        )

        test("runOnce deletes only rows whose updated_at is older than staleAfter") {
            withInMemoryDatabase {
                val nowMs = 1_730_000_000_000L
                val nowInstant = Instant.fromEpochMilliseconds(nowMs)

                // Three repos with different fixed clocks simulate rows written at different times.
                // The substrate sets updated_at from the repo's clock on every upsert.
                val freshClock = FixedClock(Instant.fromEpochMilliseconds(nowMs - 5 * 60_000))     // 5m ago — fresh
                val stale35Clock = FixedClock(Instant.fromEpochMilliseconds(nowMs - 35 * 60_000))  // 35m ago — stale
                val stale60Clock = FixedClock(Instant.fromEpochMilliseconds(nowMs - 60 * 60_000))  // 60m ago — stale

                val freshRepo = makeRepo(this, freshClock)
                val stale35Repo = makeRepo(this, stale35Clock)
                val stale60Repo = makeRepo(this, stale60Clock)
                val readRepo = makeRepo(this)

                runTest {
                    freshRepo.upsert(session("sess-fresh", "b1", nowMs - 5 * 60_000), userId = "u1")
                    stale35Repo.upsert(session("sess-stale-35", "b2", nowMs - 35 * 60_000), userId = "u2")
                    stale60Repo.upsert(session("sess-stale-60", "b3", nowMs - 60 * 60_000), userId = "u3")

                    val task = ActiveSessionCleanupTask(
                        db = this@withInMemoryDatabase,
                        clock = FixedClock(nowInstant),
                        staleAfter = 30.minutes,
                    )
                    val removed = task.runOnce()
                    removed shouldBe 2

                    // Fresh row survives; stale rows are gone
                    readRepo.getForUser("u1") shouldHaveSize 1
                    readRepo.getForUser("u2").shouldBeEmpty()
                    readRepo.getForUser("u3").shouldBeEmpty()
                }
            }
        }

        test("runOnce on an empty table returns 0 without throwing") {
            withInMemoryDatabase {
                val clock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))
                val task = ActiveSessionCleanupTask(db = this, clock = clock, staleAfter = 30.minutes)
                runTest {
                    task.runOnce() shouldBe 0
                }
            }
        }

        test("runOnce with all-fresh rows returns 0") {
            withInMemoryDatabase {
                val nowMs = 1_730_000_000_000L
                // Fresh rows — written 1 second ago (well within 30m threshold)
                val recentClock = FixedClock(Instant.fromEpochMilliseconds(nowMs - 1_000L))
                val repo = makeRepo(this, recentClock)
                val readRepo = makeRepo(this)

                runTest {
                    repeat(3) { i ->
                        repo.upsert(session("sess-fresh-$i", "book-$i", nowMs - 1_000L), userId = "u$i")
                    }

                    val task = ActiveSessionCleanupTask(
                        db = this@withInMemoryDatabase,
                        clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                        staleAfter = 30.minutes,
                    )
                    task.runOnce() shouldBe 0

                    repeat(3) { i ->
                        readRepo.getForUser("u$i") shouldHaveSize 1
                    }
                }
            }
        }
    })
