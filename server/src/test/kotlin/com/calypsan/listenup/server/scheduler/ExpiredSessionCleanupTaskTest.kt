@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExpiredSessionCleanupTaskTest :
    FunSpec({

        val pepper = "x".repeat(32).toByteArray()
        val now = Instant.parse("2026-05-24T12:00:00Z")

        fun seedUser(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            id: String,
        ) {
            transaction(db) {
                UserEntity.new(id) {
                    email = "$id@test.com"
                    emailNormalized = "$id@test.com"
                    passwordHash = "phc"
                    role = UserRoleColumn.MEMBER
                    displayName = id
                    status = UserStatusColumn.ACTIVE
                    createdAt = 1L
                    updatedAt = 1L
                }
            }
        }

        test("runOnce deletes sessions whose expiresAt is in the past; fresh sessions survive") {
            withInMemoryDatabase {
                seedUser(this, "u-1")
                seedUser(this, "u-2")

                // Expired session: born with a -1ms TTL so it expires immediately.
                val expiredSvc = SessionService(
                    this,
                    RefreshTokenHasher(pepper),
                    RefreshTokenGenerator(),
                    refreshTtl = (-1).milliseconds,
                    clock = FixedClock(now),
                )
                // Fresh session: born with a 1-hour TTL so it expires in the future.
                val freshSvc = SessionService(
                    this,
                    RefreshTokenHasher(pepper),
                    RefreshTokenGenerator(),
                    clock = FixedClock(now),
                )

                runTest {
                    val expired = expiredSvc.createSession(UserId("u-1"))
                    val fresh = freshSvc.createSession(UserId("u-2"))

                    val task = ExpiredSessionCleanupTask(
                        sessionService = SessionService(this@withInMemoryDatabase, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = FixedClock(now)),
                        clock = FixedClock(now),
                    )
                    val removed = task.runOnce()

                    removed shouldBe 1
                    // Expired session is gone — isLive is false (row deleted, not just revoked).
                    freshSvc.isLive(fresh.sessionId) shouldBe true
                    expiredSvc.isLive(expired.sessionId) shouldBe false
                }
            }
        }

        test("runOnce on an empty table returns 0 without throwing") {
            withInMemoryDatabase {
                runTest {
                    val task = ExpiredSessionCleanupTask(
                        sessionService = SessionService(this@withInMemoryDatabase, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = FixedClock(now)),
                        clock = FixedClock(now),
                    )
                    task.runOnce() shouldBe 0
                }
            }
        }

        test("runOnce with only fresh sessions returns 0") {
            withInMemoryDatabase {
                seedUser(this, "u-1")
                seedUser(this, "u-2")
                val svc = SessionService(this, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = FixedClock(now))

                runTest {
                    svc.createSession(UserId("u-1"))
                    svc.createSession(UserId("u-2"))

                    val task = ExpiredSessionCleanupTask(
                        sessionService = SessionService(this@withInMemoryDatabase, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = FixedClock(now)),
                        clock = FixedClock(now),
                    )
                    task.runOnce() shouldBe 0
                }
            }
        }
    })
