@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class ExpiredSessionCleanupTaskTest :
    FunSpec({

        val pepper = "x".repeat(32).toByteArray()
        val now = Instant.parse("2026-05-24T12:00:00Z")

        fun makeTask(sql: ListenUpDatabase) =
            ExpiredSessionCleanupTask(
                sessionService =
                    SessionService(
                        sql,
                        RefreshTokenHasher(pepper),
                        RefreshTokenGenerator(),
                        clock = FixedClock(now),
                    ),
                clock = FixedClock(now),
            )

        test("runOnce deletes sessions whose expiresAt is in the past; fresh sessions survive") {
            withSqlDatabase {
                sql.seedTestUser("u-1")
                sql.seedTestUser("u-2")

                // Expired session: born with a -1ms TTL so it expires immediately.
                val expiredSvc =
                    SessionService(
                        sql,
                        RefreshTokenHasher(pepper),
                        RefreshTokenGenerator(),
                        refreshTtl = (-1).milliseconds,
                        clock = FixedClock(now),
                    )
                // Fresh session: born with a 1-hour TTL so it expires in the future.
                val freshSvc =
                    SessionService(
                        sql,
                        RefreshTokenHasher(pepper),
                        RefreshTokenGenerator(),
                        clock = FixedClock(now),
                    )

                runTest {
                    val expired = expiredSvc.createSession(UserId("u-1"))
                    val fresh = freshSvc.createSession(UserId("u-2"))

                    val task = makeTask(sql)
                    val removed = task.runOnce()

                    removed shouldBe 1
                    // Expired session is gone — isLive is false (row deleted, not just revoked).
                    freshSvc.isLive(fresh.sessionId) shouldBe true
                    expiredSvc.isLive(expired.sessionId) shouldBe false
                }
            }
        }

        test("runOnce on an empty table returns 0 without throwing") {
            withSqlDatabase {
                runTest {
                    makeTask(sql).runOnce() shouldBe 0
                }
            }
        }

        test("runOnce with only fresh sessions returns 0") {
            withSqlDatabase {
                sql.seedTestUser("u-1")
                sql.seedTestUser("u-2")
                val svc =
                    SessionService(
                        sql,
                        RefreshTokenHasher(pepper),
                        RefreshTokenGenerator(),
                        clock = FixedClock(now),
                    )

                runTest {
                    svc.createSession(UserId("u-1"))
                    svc.createSession(UserId("u-2"))

                    makeTask(sql).runOnce() shouldBe 0
                }
            }
        }
    })
