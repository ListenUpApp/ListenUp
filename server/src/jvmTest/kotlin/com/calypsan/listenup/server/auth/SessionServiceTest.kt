@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.SessionEntity
import com.calypsan.listenup.server.db.SessionTable
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SessionServiceTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        fun freshDb(): Database {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            return DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}")).database
        }

        fun seedUser(
            db: Database,
            id: String,
        ) {
            transaction(db) {
                UserEntity.new(id) {
                    email = "$id@example.com"
                    emailNormalized = "$id@example.com"
                    passwordHash = "phc"
                    role = UserRoleColumn.MEMBER
                    displayName = id
                    status = UserStatusColumn.ACTIVE
                    createdAt = 1L
                    updatedAt = 1L
                }
            }
        }

        test("createSession persists a row and returns the raw token only once") {
            val db = freshDb()
            seedUser(db, "u-1")
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val issued = svc.createSession(UserId("u-1"), label = "iPhone")

            issued.refreshToken.value.length shouldBe 43
            transaction(db) {
                val row = SessionEntity.findById(issued.sessionId.value).shouldNotBeNull()
                row.user.id.value shouldBe "u-1"
                row.refreshTokenHash shouldBe RefreshTokenHasher(pepper).hash(issued.refreshToken.value)
                row.previousHash shouldBe null
                row.label shouldBe "iPhone"
                row.revokedAt shouldBe null
            }
        }

        test("rotate issues a new token, advances previousHash, keeps the session live") {
            val db = freshDb()
            seedUser(db, "u-1")
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val issued = svc.createSession(UserId("u-1"))
            val rotated = svc.rotate(issued.refreshToken).shouldNotBeNull()

            rotated.sessionId shouldBe issued.sessionId
            rotated.userId shouldBe UserId("u-1")
            rotated.refreshToken shouldNotBe issued.refreshToken

            transaction(db) {
                val row = SessionEntity.findById(issued.sessionId.value).shouldNotBeNull()
                row.refreshTokenHash shouldBe RefreshTokenHasher(pepper).hash(rotated.refreshToken.value)
                row.previousHash shouldBe RefreshTokenHasher(pepper).hash(issued.refreshToken.value)
                row.revokedAt shouldBe null
            }
        }

        test("rotate with an unknown token returns null and does nothing") {
            val db = freshDb()
            seedUser(db, "u-1")
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            svc.createSession(UserId("u-1"))
            val rotated = svc.rotate(RefreshToken("not-a-real-token"))

            rotated shouldBe null
        }

        test("rotate replaying the previous token revokes the entire family") {
            val db = freshDb()
            seedUser(db, "u-1")
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val issued = svc.createSession(UserId("u-1"))
            val firstRotation = svc.rotate(issued.refreshToken).shouldNotBeNull()

            // Adversary replays the original (now-stale) refresh token.
            val replay = svc.rotate(issued.refreshToken)
            replay shouldBe null

            transaction(db) {
                val row = SessionEntity.findById(issued.sessionId.value).shouldNotBeNull()
                row.revokedAt shouldNotBe null
            }

            // After family revoke, even the *current* good token can't rotate the
            // session — the row is revoked.
            svc.rotate(firstRotation.refreshToken) shouldBe null
        }

        test("revoke marks the session row revoked; revokeAll does the same for every active session") {
            val db = freshDb()
            seedUser(db, "u-1")
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val a = svc.createSession(UserId("u-1"))
            val b = svc.createSession(UserId("u-1"))

            svc.revoke(a.sessionId, UserId("u-1"))
            svc.isLive(a.sessionId) shouldBe false
            svc.isLive(b.sessionId) shouldBe true

            svc.revokeAll(UserId("u-1"))
            svc.isLive(b.sessionId) shouldBe false

            transaction(db) {
                val list =
                    SessionEntity
                        .find { SessionTable.userId eq "u-1" }
                        .toList()
                list.forEach { it.revokedAt shouldNotBe null }
            }
        }

        test("rotate returns null for an explicitly-revoked session") {
            val db = freshDb()
            seedUser(db, "u-1")
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val s = svc.createSession(UserId("u-1"))
            svc.revoke(s.sessionId, UserId("u-1"))

            svc.rotate(s.refreshToken) shouldBe null
        }

        test("rotate returns null for an expired session") {
            val db = freshDb()
            seedUser(db, "u-1")
            // Use a tiny TTL so the session is born already-expired by the test clock.
            val svc =
                SessionService(
                    db,
                    RefreshTokenHasher(pepper),
                    RefreshTokenGenerator(),
                    refreshTtl = (-1).milliseconds,
                    clock = clock,
                )

            val s = svc.createSession(UserId("u-1"))
            svc.rotate(s.refreshToken) shouldBe null
        }

        test("wasReplay distinguishes 'unknown token' from 'replayed-and-revoked'") {
            val db = freshDb()
            seedUser(db, "u-1")
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val issued = svc.createSession(UserId("u-1"))
            svc.rotate(issued.refreshToken).shouldNotBeNull()

            // The originally-issued token is now in `previous_hash` — it's a replay.
            svc.wasReplay(issued.refreshToken) shouldBe true
            // A token the server has never seen is not a replay, just unknown.
            svc.wasReplay(RefreshToken("never-issued")) shouldBe false
        }

        test("rotate finds the right session even with many active sessions") {
            val db = freshDb()
            seedUser(db, "u-1")
            seedUser(db, "u-2")
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            // Create some noise: 10 other sessions for u-2
            repeat(10) { svc.createSession(UserId("u-2")) }
            val target = svc.createSession(UserId("u-1"))
            repeat(10) { svc.createSession(UserId("u-2")) }

            val rotated = svc.rotate(target.refreshToken)
            rotated.shouldNotBeNull()
            rotated.userId shouldBe UserId("u-1")
            rotated.sessionId shouldBe target.sessionId
        }
    })
