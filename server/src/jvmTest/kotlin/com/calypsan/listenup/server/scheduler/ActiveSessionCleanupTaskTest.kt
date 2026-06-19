@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.sync.ChangeBus
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
        ): ActiveSessionRepository = ActiveSessionRepository(db = db, bus = ChangeBus(), clock = clock)

        test("runOnce deletes only rows whose updated_at is older than staleAfter") {
            withInMemoryDatabase {
                val nowMs = 1_730_000_000_000L
                val nowInstant = Instant.fromEpochMilliseconds(nowMs)

                // Three repos with different fixed clocks simulate rows written at different times.
                // startOrRefresh stamps updated_at from the repo's clock on insert.
                val freshClock = FixedClock(Instant.fromEpochMilliseconds(nowMs - 5 * 60_000)) // 5m ago — fresh
                val stale35Clock = FixedClock(Instant.fromEpochMilliseconds(nowMs - 35 * 60_000)) // 35m ago — stale
                val stale60Clock = FixedClock(Instant.fromEpochMilliseconds(nowMs - 60 * 60_000)) // 60m ago — stale

                val freshRepo = makeRepo(this, freshClock)
                val stale35Repo = makeRepo(this, stale35Clock)
                val stale60Repo = makeRepo(this, stale60Clock)
                val readRepo = makeRepo(this)

                runTest {
                    freshRepo.startOrRefresh("u1", "b1")
                    stale35Repo.startOrRefresh("u2", "b2")
                    stale60Repo.startOrRefresh("u3", "b3")

                    val task =
                        ActiveSessionCleanupTask(
                            db = this@withInMemoryDatabase,
                            bus = ChangeBus(),
                            clock = FixedClock(nowInstant),
                            staleAfter = 30.minutes,
                        )
                    val removed = task.runOnce()
                    removed shouldBe 2

                    // Fresh row survives; stale rows are gone
                    readRepo.listReadersForBook("b1", excludeUserId = "none") shouldHaveSize 1
                    readRepo.listReadersForBook("b2", excludeUserId = "none").shouldBeEmpty()
                    readRepo.listReadersForBook("b3", excludeUserId = "none").shouldBeEmpty()
                }
            }
        }

        test("runOnce on an empty table returns 0 without throwing") {
            withInMemoryDatabase {
                val clock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))
                val task = ActiveSessionCleanupTask(db = this, bus = ChangeBus(), clock = clock, staleAfter = 30.minutes)
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
                        repo.startOrRefresh("u$i", "book-$i")
                    }

                    val task =
                        ActiveSessionCleanupTask(
                            db = this@withInMemoryDatabase,
                            bus = ChangeBus(),
                            clock = FixedClock(Instant.fromEpochMilliseconds(nowMs)),
                            staleAfter = 30.minutes,
                        )
                    task.runOnce() shouldBe 0

                    repeat(3) { i ->
                        readRepo.listReadersForBook("book-$i", excludeUserId = "none") shouldHaveSize 1
                    }
                }
            }
        }
    })
