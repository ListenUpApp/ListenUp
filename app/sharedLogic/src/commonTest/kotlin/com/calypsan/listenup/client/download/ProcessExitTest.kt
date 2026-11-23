package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Which recorded process exit ended a transfer. Two deaths in a row must each be charged to the file that was moving
 * when it happened, not both to the newest.
 */
class ProcessExitTest :
    FunSpec({
        val exits = listOf(ProcessExit(at = 4_000L, reason = 13), ProcessExit(at = 2_000L, reason = 10))

        test("a transfer is ended by the first exit after it started") {
            exitThatEnded(exits, startedAt = 1_000L) shouldBe ProcessExit(at = 2_000L, reason = 10)
            exitThatEnded(exits, startedAt = 3_000L) shouldBe ProcessExit(at = 4_000L, reason = 13)
        }

        test("no exit after the start is no record, rather than a guess") {
            exitThatEnded(exits, startedAt = 5_000L) shouldBe null
        }

        test("a transfer with no start time is charged to the newest exit") {
            exitThatEnded(exits, startedAt = null) shouldBe ProcessExit(at = 4_000L, reason = 13)
        }
    })
