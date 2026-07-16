@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.migratedTestDatabase
import com.calypsan.listenup.server.testing.seedTestUser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SessionServiceTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        fun freshDb() = migratedTestDatabase().db

        test("createSession persists a row and returns the raw token only once") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val issued = svc.createSession(UserId("u-1"), label = "iPhone")

            issued.refreshToken.value.length shouldBe 43
            val row = db.sessionsQueries.selectById(issued.sessionId.value).executeAsOne()
            row.user_id shouldBe "u-1"
            row.refresh_token_hash shouldBe RefreshTokenHasher(pepper).hash(issued.refreshToken.value)
            row.previous_hash shouldBe null
            row.label shouldBe "iPhone"
            row.revoked_at shouldBe null
        }

        test("rotate issues a new token, advances previousHash, keeps the session live") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val issued = svc.createSession(UserId("u-1"))
            val rotated = svc.rotate(issued.refreshToken).shouldNotBeNull()

            rotated.sessionId shouldBe issued.sessionId
            rotated.userId shouldBe UserId("u-1")
            rotated.refreshToken shouldNotBe issued.refreshToken

            val row = db.sessionsQueries.selectById(issued.sessionId.value).executeAsOne()
            row.refresh_token_hash shouldBe RefreshTokenHasher(pepper).hash(rotated.refreshToken.value)
            row.previous_hash shouldBe RefreshTokenHasher(pepper).hash(issued.refreshToken.value)
            row.revoked_at shouldBe null
        }

        test("rotate with an unknown token returns null and does nothing") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            svc.createSession(UserId("u-1"))
            val rotated = svc.rotate(RefreshToken("not-a-real-token"))

            rotated shouldBe null
        }

        test("rotate replaying the previous token AFTER the grace window revokes the entire family") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)

            val issued = svc.createSession(UserId("u-1"))
            val firstRotation = svc.rotate(issued.refreshToken).shouldNotBeNull()

            // Adversary replays the original (now-stale) refresh token WELL beyond the lost-response
            // grace window — an unambiguous reuse attack.
            mutClock.instant = mutClock.instant + 61.seconds
            val replay = svc.rotate(issued.refreshToken)
            replay shouldBe null

            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .revoked_at shouldNotBe null

            // After family revoke, even the *current* good token can't rotate the
            // session — the row is revoked.
            svc.rotate(firstRotation.refreshToken) shouldBe null
        }

        test("a replay WITHIN the grace window rotates again and never revokes the family (C4)") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)

            val issued = svc.createSession(UserId("u-1"))
            val firstRotation = svc.rotate(issued.refreshToken).shouldNotBeNull()

            // The client never received firstRotation's response and re-presents the ORIGINAL token
            // a moment later — a lost-response retry, not an attack.
            mutClock.instant = mutClock.instant + 30.seconds
            val retry = svc.rotate(issued.refreshToken).shouldNotBeNull()

            // It rotates AGAIN — a usable fresh token — rather than family-revoking.
            retry.sessionId shouldBe issued.sessionId
            retry.userId shouldBe UserId("u-1")
            retry.refreshToken shouldNotBe firstRotation.refreshToken
            retry.refreshToken shouldNotBe issued.refreshToken

            // The session stays live throughout.
            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .revoked_at shouldBe null
            svc.isLive(issued.sessionId) shouldBe true

            // The freshly-minted token works on the next rotation (the client recovered cleanly).
            svc.rotate(retry.refreshToken).shouldNotBeNull()
        }

        test("a replay within grace is idempotent-safe but a LATE replay of the same token still revokes") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)

            val issued = svc.createSession(UserId("u-1"))
            svc.rotate(issued.refreshToken).shouldNotBeNull()

            // A grace retry keeps the family alive.
            mutClock.instant = mutClock.instant + 10.seconds
            svc.rotate(issued.refreshToken).shouldNotBeNull()

            // The same original token surfacing long after the window is an attack → family revoke.
            mutClock.instant = mutClock.instant + 61.seconds
            svc.rotate(issued.refreshToken) shouldBe null
            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .revoked_at shouldNotBe null
        }

        test("revoke marks the session row revoked; revokeAll does the same for every active session") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val a = svc.createSession(UserId("u-1"))
            val b = svc.createSession(UserId("u-1"))

            svc.revoke(a.sessionId, UserId("u-1"))
            svc.isLive(a.sessionId) shouldBe false
            svc.isLive(b.sessionId) shouldBe true

            svc.revokeAll(UserId("u-1"))
            svc.isLive(b.sessionId) shouldBe false

            db.sessionsQueries
                .selectById(a.sessionId.value)
                .executeAsOne()
                .revoked_at shouldNotBe null
            db.sessionsQueries
                .selectById(b.sessionId.value)
                .executeAsOne()
                .revoked_at shouldNotBe null
        }

        test("revokeAllExcept revokes every other session but spares the given one") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val spared = svc.createSession(UserId("u-1"))
            val other1 = svc.createSession(UserId("u-1"))
            val other2 = svc.createSession(UserId("u-1"))

            svc.revokeAllExcept(UserId("u-1"), spared.sessionId)

            svc.isLive(spared.sessionId) shouldBe true
            svc.isLive(other1.sessionId) shouldBe false
            svc.isLive(other2.sessionId) shouldBe false
        }

        test("rotate returns null for an explicitly-revoked session") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val s = svc.createSession(UserId("u-1"))
            svc.revoke(s.sessionId, UserId("u-1"))

            svc.rotate(s.refreshToken) shouldBe null
        }

        test("rotate returns null for an expired session") {
            val db = freshDb()
            db.seedTestUser("u-1")
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
            db.seedTestUser("u-1")
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

            val issued = svc.createSession(UserId("u-1"))
            svc.rotate(issued.refreshToken).shouldNotBeNull()

            // The originally-issued token is now in `previous_hash` — it's a replay.
            svc.wasReplay(issued.refreshToken) shouldBe true
            // A token the server has never seen is not a replay, just unknown.
            svc.wasReplay(RefreshToken("never-issued")) shouldBe false
        }

        test("rotate finds the right session even with many active sessions") {
            val db = freshDb()
            db.seedTestUser("u-1")
            db.seedTestUser("u-2")
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)

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
