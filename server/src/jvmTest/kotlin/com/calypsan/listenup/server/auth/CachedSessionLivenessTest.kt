package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Pins the per-connection cache that keeps ordinary-call liveness to one session read per window. */
class CachedSessionLivenessTest :
    FunSpec({

        class CountingLiveness(
            var alive: Boolean = true,
        ) : SessionLiveness {
            var reads = 0

            override suspend fun isLive(
                sessionId: SessionId,
                issuedAtEpochSeconds: Long?,
            ): Boolean {
                reads++
                return alive
            }
        }

        val principal = PrincipalProvider { UserPrincipal(UserId("u1"), SessionId("s1"), UserRole.MEMBER) }
        val window = 5.seconds

        test("a live answer is reused inside the window") {
            val fake = CountingLiveness()
            val cached = CachedSessionLiveness(principal, fake, window, TestTimeSource())

            cached.isLive() shouldBe true
            cached.isLive() shouldBe true

            fake.reads shouldBe 1
        }

        test("a live answer is re-read after the window") {
            val fake = CountingLiveness()
            val time = TestTimeSource()
            val cached = CachedSessionLiveness(principal, fake, window, time)

            cached.isLive()
            time += window + 1.seconds
            cached.isLive()

            fake.reads shouldBe 2
        }

        test("a dead answer is final and is not re-read") {
            val fake = CountingLiveness(alive = false)
            val time = TestTimeSource()
            val cached = CachedSessionLiveness(principal, fake, window, time)

            repeat(3) {
                cached.isLive() shouldBe false
                time += window + 1.seconds
            }
            fake.alive = true

            cached.isLive() shouldBe false
            fake.reads shouldBe 1
        }

        test("no principal reads as dead without touching the session table") {
            val fake = CountingLiveness()
            val cached = CachedSessionLiveness(PrincipalProvider.None, fake, window, TestTimeSource())

            cached.isLive() shouldBe false
            fake.reads shouldBe 0
        }
    })
