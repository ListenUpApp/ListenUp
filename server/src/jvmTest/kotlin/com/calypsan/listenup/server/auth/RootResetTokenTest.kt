@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RootResetTokenTest :
    FunSpec({
        val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

        test("disarmed rejects every token, including the empty string") {
            val armed = RootResetToken.disarmed()
            armed.consume("anything", start) shouldBe false
            armed.consume("", start) shouldBe false
        }

        test("armed accepts its own token exactly once") {
            val armed = RootResetToken.armed(clock = FixedClock(start))
            armed.consume(armed.token, start) shouldBe true
            armed.consume(armed.token, start) shouldBe false
        }

        test("armed rejects a wrong token without consuming the real one") {
            val armed = RootResetToken.armed(clock = FixedClock(start))
            armed.consume("wrong", start) shouldBe false
            armed.consume(armed.token, start) shouldBe true
        }

        test("the window closes after 15 minutes") {
            val armed = RootResetToken.armed(clock = FixedClock(start))
            armed.consume(armed.token, start.plus(16.minutes)) shouldBe false
        }

        test("the token is long enough not to be guessable") {
            RootResetToken.armed(clock = FixedClock(start)).token.length shouldBe 22
        }
    })
