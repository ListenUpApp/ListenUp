@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.server.di.refreshReuseGracePeriod
import com.calypsan.listenup.server.logging.ListenUpLoggerFactory
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.migratedTestDatabase
import com.calypsan.listenup.server.testing.seedTestUser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.config.MapApplicationConfig
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.slf4j.event.Level

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
            // The rightful client received that reply and used its access token.
            svc.confirmReplyReceived(issued, mutClock)

            // Adversary replays the original (now-stale) refresh token beyond the lost-response
            // grace window — an unambiguous reuse attack.
            mutClock.instant = mutClock.instant + 31.minutes
            // installTestCapture() mutates the JVM-global SLF4J factory and forces DEBUG for every
            // logger. Safe only because :server:jvmTest runs specs sequentially — under concurrent
            // specs one test's capture would swallow another's events.
            val capture = ListenUpLoggerFactory.installTestCapture()
            val replay =
                try {
                    val result = svc.rotate(issued.refreshToken)

                    // The revocation must be visible in server logs (today's incident required DB
                    // spelunking to diagnose) — WARN with identifiers only, no token material.
                    val warn =
                        capture.events
                            .firstOrNull { it.level == Level.WARN && it.message.contains("Revoking session family") }
                            .shouldNotBeNull()
                    warn.message shouldContain issued.sessionId.value
                    warn.message shouldContain "u-1"
                    // Assert the whole phrase: "16 min" also matched "116 min".
                    warn.message shouldContain "31 min since last rotation"
                    warn.message shouldNotContain issued.refreshToken.value
                    warn.message shouldNotContain firstRotation.refreshToken.value
                    result
                } finally {
                    ListenUpLoggerFactory.removeTestCapture()
                }
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

            // The client never received (or never persisted) firstRotation's response and re-presents
            // the ORIGINAL token on its next background sync — a lost-response retry, not an attack.
            // 14 minutes exercises the mobile reality: process death between rotation and persist
            // surfaces on the next sync cadence, not within seconds.
            mutClock.instant = mutClock.instant + 14.minutes
            val capture = ListenUpLoggerFactory.installTestCapture()
            val retry =
                try {
                    val result = svc.rotate(issued.refreshToken).shouldNotBeNull()

                    // The benign path is visible at INFO so future incidents show up in logs.
                    val info =
                        capture.events
                            .firstOrNull { it.level == Level.INFO && it.message.contains("Grace re-rotation") }
                            .shouldNotBeNull()
                    info.message shouldContain issued.sessionId.value
                    info.message shouldContain "840 s since last rotation"
                    info.message shouldNotContain issued.refreshToken.value
                    result
                } finally {
                    ListenUpLoggerFactory.removeTestCapture()
                }

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

            // A grace retry keeps the family alive, and the client receives and uses its reply.
            mutClock.instant = mutClock.instant + 10.seconds
            svc.rotate(issued.refreshToken).shouldNotBeNull()
            svc.confirmReplyReceived(issued, mutClock)

            // The same original token surfacing long after the window is an attack → family revoke.
            mutClock.instant = mutClock.instant + 31.minutes
            svc.rotate(issued.refreshToken) shouldBe null
            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .revoked_at shouldNotBe null
        }

        test("a grace re-rotation does not re-arm the window, and does not extend the refresh TTL") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)

            val issued = svc.createSession(UserId("u-1"))
            svc.rotate(issued.refreshToken).shouldNotBeNull()
            val afterNormalRotation =
                db.sessionsQueries.selectById(issued.sessionId.value).executeAsOne()

            // A lost-response retry well inside the window — accepted, as it should be — and this
            // time the reply lands and is used.
            mutClock.instant = mutClock.instant + 20.minutes
            svc.rotate(issued.refreshToken).shouldNotBeNull()
            svc.confirmReplyReceived(issued, mutClock)

            // The grace branch must preserve BOTH anchors. If it wrote last_used_at = now the
            // window would re-arm on every replay (a captured token replayed once per window would
            // be valid forever); if it wrote a fresh expires_at each replay would renew the 30-day
            // refresh TTL too.
            val afterGrace = db.sessionsQueries.selectById(issued.sessionId.value).executeAsOne()
            afterGrace.last_used_at shouldBe afterNormalRotation.last_used_at
            afterGrace.expires_at shouldBe afterNormalRotation.expires_at

            // 40 minutes after the last NORMAL rotation, the same original token is outside the
            // window: the replay chain terminates in a family revoke instead of renewing itself.
            mutClock.instant = mutClock.instant + 20.minutes
            svc.rotate(issued.refreshToken) shouldBe null
            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .revoked_at shouldNotBe null
        }

        // Replays the pre-rotation token [elapsed] after the only normal rotation, against the
        // production default window. Returns true iff the replay was accepted as a grace retry.
        suspend fun graceAcceptedAfter(elapsed: Duration): Boolean {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)

            val issued = svc.createSession(UserId("u-1"))
            svc.rotate(issued.refreshToken).shouldNotBeNull()
            svc.confirmReplyReceived(issued, mutClock)
            mutClock.instant = mutClock.instant + elapsed
            val replay = svc.rotate(issued.refreshToken)

            val revoked =
                db.sessionsQueries
                    .selectById(issued.sessionId.value)
                    .executeAsOne()
                    .revoked_at != null
            // Accepted ⇔ not revoked: the two outcomes are exclusive, never both or neither.
            (replay != null) shouldBe !revoked
            return replay != null
        }

        // ── Lost reply vs. replay after grace (2026-09-30) ──────────────────────────────────────
        //
        // A client killed or frozen mid-refresh never receives the rotation's reply, keeps the old
        // refresh token, and presents it on its next launch — often more than 30 minutes later.
        // That used to revoke the whole family. The server cannot tell it from theft by the refresh
        // token alone; what tells them apart is whether an access token from the new rotation was
        // ever used — proof the reply arrived.

        test("a replay after grace while the rotation is unconfirmed is a lost reply: it rotates and never revokes") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)
            val issued = svc.createSession(UserId("u-1"))
            val lostReply = svc.rotate(issued.refreshToken).shouldNotBeNull()

            mutClock.instant = mutClock.instant + 3.hours
            val recovered = svc.rotate(issued.refreshToken).shouldNotBeNull()

            recovered.sessionId shouldBe issued.sessionId
            recovered.refreshToken shouldNotBe lostReply.refreshToken
            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .revoked_at shouldBe null
            svc.isLive(issued.sessionId) shouldBe true
            // The unused token from the lost reply is dead; the recovered one is the live pair.
            svc.rotate(lostReply.refreshToken) shouldBe null
            svc.rotate(recovered.refreshToken).shouldNotBeNull()
        }

        test("a replay after grace once the rotation is confirmed still revokes the whole family") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)
            val issued = svc.createSession(UserId("u-1"))
            val rotated = svc.rotate(issued.refreshToken).shouldNotBeNull()
            mutClock.instant = mutClock.instant + 5.minutes
            svc.confirmReplyReceived(issued, mutClock, issuedAt = mutClock.instant - 5.minutes)

            mutClock.instant = mutClock.instant + 3.hours
            svc.rotate(issued.refreshToken) shouldBe null

            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .revoked_at shouldNotBe null
            svc.rotate(rotated.refreshToken) shouldBe null
        }

        test("a replay within grace rotates as before, confirmed or not") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)
            val issued = svc.createSession(UserId("u-1"))
            svc.rotate(issued.refreshToken).shouldNotBeNull()
            svc.confirmReplyReceived(issued, mutClock)

            mutClock.instant = mutClock.instant + 10.minutes
            svc.rotate(issued.refreshToken).shouldNotBeNull()

            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .revoked_at shouldBe null
        }

        test("only an access token issued at or after the latest rotation confirms it") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)
            val issued = svc.createSession(UserId("u-1"))

            fun confirmedAt() =
                db.sessionsQueries
                    .selectById(issued.sessionId.value)
                    .executeAsOne()
                    .rotation_confirmed_at

            // A token minted before the rotation — the client still on its old access token.
            mutClock.instant = mutClock.instant + 10.minutes
            val beforeRotation = mutClock.instant - 1.seconds
            val first = svc.rotate(issued.refreshToken).shouldNotBeNull()
            svc.isLive(issued.sessionId, issuedAtEpochSeconds = beforeRotation.epochSeconds) shouldBe true
            confirmedAt() shouldBe null

            // The rotation's own token confirms it, once: later uses don't rewrite the moment.
            val atRotation = mutClock.instant
            mutClock.instant = mutClock.instant + 1.minutes
            svc.isLive(issued.sessionId, issuedAtEpochSeconds = atRotation.epochSeconds) shouldBe true
            val confirmed = confirmedAt().shouldNotBeNull()
            mutClock.instant = mutClock.instant + 1.minutes
            repeat(5) { svc.isLive(issued.sessionId, issuedAtEpochSeconds = atRotation.epochSeconds) }
            confirmedAt() shouldBe confirmed

            // A new rotation resets it, and the previous rotation's token can't confirm the new one.
            mutClock.instant = mutClock.instant + 10.minutes
            svc.rotate(first.refreshToken).shouldNotBeNull()
            confirmedAt() shouldBe null
            svc.isLive(issued.sessionId, issuedAtEpochSeconds = atRotation.epochSeconds) shouldBe true
            confirmedAt() shouldBe null
        }

        // Confirmation is bookkeeping for the lost-reply rule; authentication must never hang on it.
        // Under WAL contention the write can fail (SQLITE_BUSY past the timeout) — the request it
        // rides on is still a valid, live session and must authenticate.
        test("a confirmation write that fails still authenticates the request") {
            val test = migratedTestDatabase()
            val db = test.db
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)
            val issued = svc.createSession(UserId("u-1"))
            svc.rotate(issued.refreshToken).shouldNotBeNull()
            test.driver.execute(
                null,
                "CREATE TRIGGER fail_confirm BEFORE UPDATE OF rotation_confirmed_at ON sessions " +
                    "BEGIN SELECT RAISE(ABORT, 'database is locked'); END",
                0,
            )

            svc.isLive(issued.sessionId, issuedAtEpochSeconds = mutClock.instant.epochSeconds) shouldBe true

            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .rotation_confirmed_at shouldBe null
        }

        test("a confirmation for a rotation that has since been superseded is never written") {
            val db = freshDb()
            db.seedTestUser("u-1")
            val mutClock = MutableClock(Instant.parse("2026-05-02T12:00:00Z"))
            val svc = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = mutClock)
            val issued = svc.createSession(UserId("u-1"))
            val first = svc.rotate(issued.refreshToken).shouldNotBeNull()
            val firstRotatedAt =
                db.sessionsQueries
                    .selectById(issued.sessionId.value)
                    .executeAsOne()
                    .rotated_at
                    .shouldNotBeNull()
            mutClock.instant = mutClock.instant + 1.minutes
            svc.rotate(first.refreshToken).shouldNotBeNull()

            // A request that read the row before the second rotation writes its confirmation late.
            db.sessionsQueries.confirmRotation(
                confirmed_at = mutClock.instant.toEpochMilliseconds(),
                id = issued.sessionId.value,
                rotated_at = firstRotatedAt,
            )

            db.sessionsQueries
                .selectById(issued.sessionId.value)
                .executeAsOne()
                .rotation_confirmed_at shouldBe null
        }

        test("the grace window is exactly 30 minutes, and its final millisecond is inside it") {
            // The 14 min / 16 min cases elsewhere in this file are satisfied by ANY window in
            // [14, 16) — the number itself was untested. These pin it to the millisecond, and pin
            // the predicate's inclusivity (`<=`, so exactly 30 minutes is still a grace retry).
            graceAcceptedAfter(30.minutes - 1.milliseconds) shouldBe true
            graceAcceptedAfter(30.minutes) shouldBe true
            graceAcceptedAfter(30.minutes + 1.milliseconds) shouldBe false
        }

        test("the DI-resolved default window IS SessionService's constant, and the override still wins") {
            // There were two constants for one window — SessionService.DEFAULT_REUSE_GRACE and
            // AuthModule's own DEFAULT_REUSE_GRACE_SECONDS — and the module's always won, so the
            // service's copy was dead code free to drift. This pins the single source of truth.
            MapApplicationConfig().refreshReuseGracePeriod() shouldBe SessionService.DEFAULT_REUSE_GRACE
            SessionService.DEFAULT_REUSE_GRACE shouldBe 30.minutes
            // TestApplicationConfig / AuthEndToEndFixture set 0 to pin the family-revoke path
            // against a real Clock.System — the runtime override must keep working.
            MapApplicationConfig("auth.refreshReuseGraceSeconds" to "0")
                .refreshReuseGracePeriod() shouldBe Duration.ZERO
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

/** The rightful client used an access token minted at [issuedAt] (default: now) — the reply arrived. */
private suspend fun SessionService.confirmReplyReceived(
    issued: IssuedSession,
    clock: MutableClock,
    issuedAt: Instant = clock.instant,
) {
    isLive(issued.sessionId, issuedAtEpochSeconds = issuedAt.epochSeconds) shouldBe true
}
