@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
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

                    // A push token bound to each session — the expired one should be swept as an
                    // orphan (deleteOrphaned) once its session row is hard-deleted; the token bound
                    // to the live session must survive.
                    sql.pushTokensQueries.upsert(
                        token = "token-expired",
                        platform = "ANDROID",
                        session_id = expired.sessionId.value,
                        user_id = "u-1",
                        now = now.toEpochMilliseconds(),
                    )
                    sql.pushTokensQueries.upsert(
                        token = "token-fresh",
                        platform = "IOS",
                        session_id = fresh.sessionId.value,
                        user_id = "u-2",
                        now = now.toEpochMilliseconds(),
                    )

                    val task = makeTask(sql)
                    val removed = task.runOnce()

                    removed shouldBe 1
                    // Expired session is gone — isLive is false (row deleted, not just revoked).
                    freshSvc.isLive(fresh.sessionId) shouldBe true
                    expiredSvc.isLive(expired.sessionId) shouldBe false

                    // The orphaned push token (bound to the now-deleted session) is swept; the
                    // token bound to the live session survives.
                    sql.pushTokensQueries.countAll().executeAsOne() shouldBe 1
                    sql.pushTokensQueries
                        .selectLiveForUser(user_id = "u-2", now = now.toEpochMilliseconds())
                        .executeAsList()
                        .map { it.token } shouldBe listOf("token-fresh")
                }
            }
        }

        test("orphan sweep cleans push tokens even without FK enforcement (production runs FK-off)") {
            // Production connections do NOT enforce foreign keys (see DriverFactory / the
            // withSqlDatabase fixture comment), so push_tokens' ON DELETE CASCADE never fires
            // there — the deleteOrphaned sweep inside SessionService.deleteExpired is the real
            // cleanup path. The shared fixtures open FK-ON drivers, where the cascade masks the
            // sweep; this test opens an FK-OFF driver (matching production) so only the sweep
            // can remove the orphaned row.
            val tmp =
                Files.createTempFile("listenup-push-orphan-", ".db").toFile().apply { deleteOnExit() }
            DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
            val driver = JdbcSqliteDriver("jdbc:sqlite:${tmp.absolutePath}")
            try {
                val sql = ListenUpDatabase(driver)
                sql.seedTestUser("u-1")
                val expiredSvc =
                    SessionService(
                        sql,
                        RefreshTokenHasher(pepper),
                        RefreshTokenGenerator(),
                        refreshTtl = (-1).milliseconds,
                        clock = FixedClock(now),
                    )

                runTest {
                    val expired = expiredSvc.createSession(UserId("u-1"))
                    sql.pushTokensQueries.upsert(
                        token = "token-orphaned",
                        platform = "ANDROID",
                        session_id = expired.sessionId.value,
                        user_id = "u-1",
                        now = now.toEpochMilliseconds(),
                    )

                    makeTask(sql).runOnce() shouldBe 1

                    // No cascade fired (FK off) — only the deleteOrphaned sweep can have removed it.
                    sql.pushTokensQueries.countAll().executeAsOne() shouldBe 0
                }
            } finally {
                driver.close()
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
