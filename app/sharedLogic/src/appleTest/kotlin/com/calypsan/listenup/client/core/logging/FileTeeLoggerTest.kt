package com.calypsan.listenup.client.core.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random

/** A stand-in for the Darwin (OSLog) logger: records what reaches it, honours a level floor. */
private class RecordingDarwinLogger(
    override val name: String,
    private val enabledFrom: Level = Level.TRACE,
) : KLogger {
    val received = mutableListOf<Triple<Level, String?, Throwable?>>()

    override fun isLoggingEnabledFor(
        level: Level,
        marker: Marker?,
    ): Boolean = level != Level.OFF && level.ordinal >= enabledFrom.ordinal

    override fun at(
        level: Level,
        marker: Marker?,
        block: KLoggingEventBuilder.() -> Unit,
    ) {
        val event = KLoggingEventBuilder().apply(block)
        received += Triple(level, event.message, event.cause)
    }
}

/**
 * [FileTeeLogger] — the iOS logging tap. kotlin-logging's Darwin backend writes to OSLog and offers
 * no appender hook, so the tee wraps each Darwin logger: every event still reaches OSLog unchanged,
 * and the same event is written to the shared log file with its stack trace.
 *
 * The level methods (`info {}`, `error(t) {}`) are interface defaults that route through `at` —
 * which is why the tee implements `KLogger` itself rather than delegating with `by`: a delegated
 * `error(t) {}` would go straight to OSLog and never reach the file.
 */
class FileTeeLoggerTest :
    FunSpec({
        val root = Path(SystemTemporaryDirectory, "file-tee-logger-${Random.nextLong()}")

        beforeTest { LogSinkRegistry.resetForTests() }
        afterTest { LogSinkRegistry.resetForTests() }

        suspend fun fileLinesAfter(
            name: String,
            body: () -> Unit,
        ): String {
            val dir = Path(root, name)
            val sink = FileLogSink(directory = dir, dispatcher = Dispatchers.IO)
            LogSinkRegistry.attach(sink)
            body()
            sink.close()
            return SystemFileSystem.source(Path(dir, FileLogSink.FILE_NAME)).buffered().use { it.readString() }
        }

        test("an event reaches the Darwin logger unchanged and the file with its stack trace") {
            val darwin = RecordingDarwinLogger("com.calypsan.listenup.Sync")
            val failure = IllegalStateException("socket closed")

            val file =
                fileLinesAfter("unchanged") {
                    FileTeeLogger(darwin).error(failure) { "sync failed" }
                }

            darwin.received shouldHaveSize 1
            darwin.received.single().first shouldBe Level.ERROR
            darwin.received.single().second shouldBe "sync failed"
            darwin.received.single().third shouldBeSameInstanceAs failure
            file shouldContain "ERROR com.calypsan.listenup.Sync - sync failed"
            file shouldContain "IllegalStateException"
            file shouldContain "socket closed"
        }

        test("a level the Darwin logger has disabled reaches neither the logger nor the file") {
            val darwin = RecordingDarwinLogger("quiet", enabledFrom = Level.INFO)

            val file =
                fileLinesAfter("disabled") {
                    FileTeeLogger(darwin).debug { "too chatty" }
                }

            darwin.received.shouldBeEmpty()
            file.contains("too chatty") shouldBe false
        }

        test("the factory wraps every logger it hands out") {
            val factory =
                FileTeeLoggerFactory(
                    object : KLoggerFactory {
                        override fun logger(name: String): KLogger = RecordingDarwinLogger(name)
                    },
                )

            val logger = factory.logger("com.calypsan.listenup.Anything")

            (logger is FileTeeLogger) shouldBe true
            logger.name shouldBe "com.calypsan.listenup.Anything"
        }

        test("a native log line from Swift lands in the same file") {
            val file =
                fileLinesAfter("native") {
                    appendNativeLogLine(level = "WARN", category = "SettingsView", message = "share sheet failed — detail")
                }

            file shouldContain "WARN  SettingsView - share sheet failed — detail"
        }
    })
