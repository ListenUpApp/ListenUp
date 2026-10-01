package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Push and pull take turns per user, and honour one pause between them. */
class HardcoverUserGateTest :
    FunSpec({

        test("one user's conversations take turns; another user's never wait") {
            runTest {
                val gate = HardcoverUserGate()
                val inside = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val first =
                    launch {
                        gate.withUser("u1") {
                            inside.complete(Unit)
                            release.await()
                        }
                    }
                inside.await()

                var secondRan = false
                val second = launch { gate.withUser("u1") { secondRan = true } }
                var otherRan = false
                gate.withUser("u2") { otherRan = true }
                runCurrent()

                otherRan shouldBe true
                secondRan shouldBe false
                release.complete(Unit)
                joinAll(first, second)
                secondRan shouldBe true
            }
        }

        test("a pause only ever moves later, and belongs to one user") {
            val gate = HardcoverUserGate()
            gate.pause("u1", untilMs = 100L)
            gate.pause("u1", untilMs = 50L)
            gate.pausedUntil("u1") shouldBe 100L
            gate.pausedUntil("u2") shouldBe null
        }
    })
