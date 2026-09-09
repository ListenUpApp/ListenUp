@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class ExpiredPasswordResetCleanupTaskTest :
    FunSpec({

        val now = Instant.parse("2026-08-10T12:00:00Z")

        fun ListenUpDatabase.insertRequest(
            id: String,
            userId: String,
            expiresAt: Long,
            pending: Boolean = false,
        ) {
            // insert() always writes PENDING; markExpired flips it when the case calls for that.
            passwordResetRequestsQueries.insert(
                id = id,
                user_id = userId,
                requested_at = expiresAt - 15.minutes.inWholeMilliseconds,
                expires_at = expiresAt,
                status = "PENDING",
                device_claim_hash = "hash",
            )
            if (!pending) passwordResetRequestsQueries.markExpired(id)
        }

        test("runOnce deletes rows expired more than a day ago; a recently-expired row survives") {
            withSqlDatabase {
                sql.seedTestUser("u-1")
                sql.seedTestUser("u-2")

                // Expired 2 days ago — past the 1-day retention window.
                sql.insertRequest("old", "u-1", expiresAt = (now - 2.days).toEpochMilliseconds())
                // Expired 1 hour ago — still within the 1-day retention window.
                sql.insertRequest("recent", "u-2", expiresAt = (now - 60.minutes).toEpochMilliseconds())

                val task = ExpiredPasswordResetCleanupTask(db = sql, clock = FixedClock(now))

                runTest {
                    task.runOnce() shouldBe 1
                    sql.passwordResetRequestsQueries.selectById("old").executeAsOneOrNull() shouldBe null
                    sql.passwordResetRequestsQueries.selectById("recent").executeAsOneOrNull() shouldNotBe null
                }
            }
        }

        test("runOnce on an empty table returns 0 without throwing") {
            withSqlDatabase {
                val task = ExpiredPasswordResetCleanupTask(db = sql, clock = FixedClock(now))
                runTest {
                    task.runOnce() shouldBe 0
                }
            }
        }

        test("runOnce leaves a live PENDING request alone regardless of age") {
            withSqlDatabase {
                sql.seedTestUser("u-1")
                // A PENDING row that would look "old" by requested_at, but its expires_at is still
                // far in the future — the sweep must key off expires_at, not requested_at.
                sql.insertRequest(
                    "still-pending",
                    "u-1",
                    expiresAt = (now + 10.minutes).toEpochMilliseconds(),
                    pending = true,
                )

                val task = ExpiredPasswordResetCleanupTask(db = sql, clock = FixedClock(now))
                runTest {
                    task.runOnce() shouldBe 0
                    sql.passwordResetRequestsQueries.selectById("still-pending").executeAsOneOrNull() shouldNotBe null
                }
            }
        }

        // A server restarted more often than the interval used to never sweep at all. The interval
        // here is a year, so the only sweep that can land inside this test is the boot sweep.
        // runBlocking rather than runTest: the sweep crosses the real SQL dispatcher, which virtual
        // time cannot follow.
        test("start runs a sweep immediately rather than waiting a full interval") {
            withSqlDatabase {
                sql.seedTestUser("u-1")
                sql.insertRequest("old", "u-1", expiresAt = (now - 2.days).toEpochMilliseconds())
                val settings = ServerSettingsRepository(sql, RegistrationPolicy.OPEN)
                val task =
                    ExpiredPasswordResetCleanupTask(
                        db = sql,
                        clock = FixedClock(now),
                        interval = 365.days,
                        settings = settings,
                        startJitter = Duration.ZERO,
                    )

                runBlocking {
                    val job = task.start(CoroutineScope(Dispatchers.Default + SupervisorJob()))
                    try {
                        eventually(5.seconds) {
                            sql.passwordResetRequestsQueries.selectById("old").executeAsOneOrNull() shouldBe null
                            settings.getValue(ExpiredPasswordResetCleanupTask.LAST_RUN_KEY) shouldBe
                                now.toEpochMilliseconds().toString()
                        }
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            }
        }
    })
