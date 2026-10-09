package com.calypsan.listenup.server.logging

import io.github.oshai.kotlinlogging.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.time.Instant

/**
 * What a log line says. On 2026-10-09 a native server's only record of a failure was its exception's message —
 * no class, no frames — so a line must carry the whole throwable, and a file line must say when it happened.
 */
class LogLinesTest :
    FunSpec({
        test("a line names its level and the logger's simple name, as the console always has") {
            renderLogLine(Level.INFO, "com.calypsan.listenup.server.scanner.ScanOrchestrator", "scan started", null) shouldBe
                "INFO: [ScanOrchestrator] scan started"
        }

        test("a failure's line carries the exception's class, message, cause and stack, not just its message") {
            val failure = IllegalStateException("decode failed", IllegalArgumentException("no serializer"))

            val line = renderLogLine(Level.ERROR, "rpc.MatchingService", "Uncaught exception", failure)

            line shouldStartWith "ERROR: [MatchingService] Uncaught exception\n"
            line shouldContain "IllegalStateException: decode failed"
            line shouldContain "IllegalArgumentException: no serializer"
            line shouldContain
                failure
                    .stackTraceToString()
                    .lineSequence()
                    .drop(1)
                    .first()
                    .trim()
        }

        test("a file line starts with when it was written, in UTC") {
            fileLogLine(Instant.parse("2026-10-09T18:11:41.123Z"), "WARN: [Logging] hello") shouldBe
                "2026-10-09T18:11:41.123Z WARN: [Logging] hello"
        }

        test("a console-only logger's lines never reach the file — the root-reset token must not persist") {
            writesToLogFile(CONSOLE_ONLY_LOGGER) shouldBe false
            writesToLogFile("com.calypsan.listenup.server.scanner.ScanOrchestrator") shouldBe true
        }

        test("LISTENUP_LOG_LEVEL names a level in any case; anything else keeps the default") {
            parseLogLevel("debug") shouldBe Level.DEBUG
            parseLogLevel("WARN") shouldBe Level.WARN
            parseLogLevel(null) shouldBe null
            parseLogLevel("loud") shouldBe null
        }
    })
