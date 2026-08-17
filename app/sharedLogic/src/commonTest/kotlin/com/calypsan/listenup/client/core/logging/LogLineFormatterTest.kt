package com.calypsan.listenup.client.core.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.TimeZone

/**
 * Pins the on-disk log line shape to the pattern users already know from the
 * desktop logback console: `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger - message`.
 */
class LogLineFormatterTest :
    FunSpec({

        test("formats timestamp, thread, padded level, logger and message") {
            formatLogLine(
                epochMillis = 0,
                level = "INFO",
                thread = "main",
                loggerName = "com.example.Thing",
                message = "hello world",
                timeZone = TimeZone.UTC,
            ) shouldBe "1970-01-01 00:00:00.000 [main] INFO  com.example.Thing - hello world"
        }

        test("pads sub-second timestamps and keeps five-char level column") {
            formatLogLine(
                epochMillis = 42,
                level = "ERROR",
                thread = "worker-1",
                loggerName = "a.B",
                message = "boom",
                timeZone = TimeZone.UTC,
            ) shouldBe "1970-01-01 00:00:00.042 [worker-1] ERROR a.B - boom"
        }

        test("renders a missing thread name as a dash") {
            formatLogLine(
                epochMillis = 0,
                level = "WARN",
                thread = null,
                loggerName = "a.B",
                message = "m",
                timeZone = TimeZone.UTC,
            ) shouldBe "1970-01-01 00:00:00.000 [-] WARN  a.B - m"
        }

        test("appends the throwable stack trace on following lines") {
            val line =
                formatLogLine(
                    epochMillis = 0,
                    level = "ERROR",
                    thread = "main",
                    loggerName = "a.B",
                    message = "failed",
                    throwable = IllegalStateException("kaput"),
                    timeZone = TimeZone.UTC,
                )

            line shouldStartWith "1970-01-01 00:00:00.000 [main] ERROR a.B - failed\n"
            line shouldContain "IllegalStateException"
            line shouldContain "kaput"
        }
    })
