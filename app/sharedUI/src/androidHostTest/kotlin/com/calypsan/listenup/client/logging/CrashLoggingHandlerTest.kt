package com.calypsan.listenup.client.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * [CrashLoggingHandler] — what an uncaught exception leaves behind before the process dies.
 *
 * The order is the contract: log the throwable, flush the file so the line is on disk, THEN hand
 * over to whichever handler was installed before (the platform's, which kills the process). Logging
 * after delegating would log nothing — there is no "after".
 */
class CrashLoggingHandlerTest :
    FunSpec({
        /** A logger that appends what it is given to [steps] and keeps the throwable. */
        class StepLogger(
            private val steps: MutableList<String>,
        ) : KLogger {
            var cause: Throwable? = null
            var level: Level? = null

            override val name: String = "crash"

            override fun isLoggingEnabledFor(
                level: Level,
                marker: Marker?,
            ): Boolean = true

            override fun at(
                level: Level,
                marker: Marker?,
                block: KLoggingEventBuilder.() -> Unit,
            ) {
                val event = KLoggingEventBuilder().apply(block)
                this.level = level
                cause = event.cause
                steps += "log"
            }
        }

        test("logs the throwable at ERROR, flushes, then delegates to the previous handler") {
            val steps = mutableListOf<String>()
            val logger = StepLogger(steps)
            var delegatedThread: Thread? = null
            var delegatedThrowable: Throwable? = null
            val previous =
                Thread.UncaughtExceptionHandler { thread, throwable ->
                    steps += "previous"
                    delegatedThread = thread
                    delegatedThrowable = throwable
                }
            val crash = IllegalStateException("boom")
            val thread = Thread.currentThread()

            CrashLoggingHandler(previous = previous, flush = { steps += "flush" }, logger = logger)
                .uncaughtException(thread, crash)

            steps shouldBe listOf("log", "flush", "previous")
            logger.level shouldBe Level.ERROR
            logger.cause shouldBeSameInstanceAs crash
            delegatedThread shouldBeSameInstanceAs thread
            delegatedThrowable shouldBeSameInstanceAs crash
        }

        test("a flush that fails still hands the crash to the previous handler") {
            val steps = mutableListOf<String>()
            val previous = Thread.UncaughtExceptionHandler { _, _ -> steps += "previous" }

            CrashLoggingHandler(
                previous = previous,
                flush = { error("disk gone") },
                logger = StepLogger(steps),
            ).uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

            steps shouldBe listOf("log", "previous")
        }

        test("with no previous handler it logs and flushes without failing") {
            val steps = mutableListOf<String>()

            CrashLoggingHandler(previous = null, flush = { steps += "flush" }, logger = StepLogger(steps))
                .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

            steps shouldBe listOf("log", "flush")
        }
    })
