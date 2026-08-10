@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RootResetTokenTest :
    FunSpec({
        val start = Instant.fromEpochMilliseconds(1_700_000_000_000)

        test("disarmed rejects every token, including the empty string") {
            val disarmed = RootResetToken.disarmed()
            disarmed.consume("anything", start).shouldBeInstanceOf<ConsumeOutcome.Rejected>().reason shouldBe
                ConsumeOutcome.Reason.UNARMED
            disarmed.consume("", start).shouldBeInstanceOf<ConsumeOutcome.Rejected>().reason shouldBe
                ConsumeOutcome.Reason.UNARMED
        }

        test("armed accepts its own token exactly once") {
            val armed = RootResetToken.armed(clock = FixedClock(start))
            armed.consume(armed.token, start) shouldBe ConsumeOutcome.Consumed
            armed.consume(armed.token, start).shouldBeInstanceOf<ConsumeOutcome.Rejected>().reason shouldBe
                ConsumeOutcome.Reason.ALREADY_CONSUMED
        }

        test("armed rejects a wrong token without consuming the real one") {
            val armed = RootResetToken.armed(clock = FixedClock(start))
            armed.consume("wrong", start).shouldBeInstanceOf<ConsumeOutcome.Rejected>().reason shouldBe
                ConsumeOutcome.Reason.WRONG_TOKEN
            armed.consume(armed.token, start) shouldBe ConsumeOutcome.Consumed
        }

        test("the window closes after 15 minutes") {
            val armed = RootResetToken.armed(clock = FixedClock(start))
            armed
                .consume(armed.token, start.plus(16.minutes))
                .shouldBeInstanceOf<ConsumeOutcome.Rejected>()
                .reason shouldBe ConsumeOutcome.Reason.EXPIRED
        }

        test("the expiry boundary is inclusive — exactly WINDOW after arming still succeeds") {
            val armed = RootResetToken.armed(clock = FixedClock(start))
            armed.consume(armed.token, start.plus(RootResetToken.WINDOW)) shouldBe ConsumeOutcome.Consumed
        }

        test("the token is 22 characters (128-bit base64url)") {
            RootResetToken.armed(clock = FixedClock(start)).token.length shouldBe 22
        }

        test("consume grants success to exactly one of many concurrent callers") {
            // Deliberately real JVM `Thread`s synchronized on a `CyclicBarrier`, NOT
            // kotlinx.coroutines.test.runTest (its TestDispatcher runs everything on one
            // virtual-time thread — no race is possible at all) and NOT
            // async(Dispatchers.Default) either (empirically: 200 coroutines released via a
            // single CompletableDeferred gate never reproduced the race in five consecutive
            // local runs against a deliberately broken read-then-write implementation — the
            // coroutine scheduler's own dispatch overhead was enough to keep serializing the
            // handful of instructions inside consume()). A CyclicBarrier parks every thread and
            // releases them in the same instant, which is what it takes to land two threads
            // inside consume()'s check-then-act window at once.
            //
            // Testing RootResetToken directly (not through RootPasswordResetService) matters
            // too: going through the service would drag real Argon2 hashing into the loop,
            // which staggers callers enough to hide exactly this class of race — mirrors the
            // lesson from PasswordResetServiceTest's "of two concurrent decisions on the same
            // ticket" test.
            //
            // Repeated over many trials (fresh token each time, since a token is single-use):
            // one CAS-timing race is not guaranteed to land on any single attempt, but the
            // correct compareAndSet implementation can NEVER fail here regardless of trial
            // count — a CAS is atomic by construction, so this loop costs nothing in
            // correctness and only buys detection power for a regression.
            repeat(TRIALS) {
                val armed = RootResetToken.armed(clock = FixedClock(start))
                val barrier = CyclicBarrier(CALLERS_PER_TRIAL)
                val results = ConcurrentLinkedQueue<ConsumeOutcome>()

                val threads =
                    List(CALLERS_PER_TRIAL) {
                        Thread {
                            barrier.await()
                            results += armed.consume(armed.token, start)
                        }
                    }
                threads.forEach { it.start() }
                threads.forEach { it.join() }

                results.count { it is ConsumeOutcome.Consumed } shouldBe 1
            }
        }
    })

private const val TRIALS = 50
private const val CALLERS_PER_TRIAL = 32
