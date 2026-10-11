package com.calypsan.listenup.client.core.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Contract of the [LogSinkRegistry] bridge: pre-attach lines are buffered and replayed
 * strictly ahead of post-attach lines, and the pre-attach buffer drops oldest at capacity.
 */
class LogSinkRegistryTest :
    FunSpec({

        beforeTest { LogSinkRegistry.resetForTests() }
        afterTest { LogSinkRegistry.resetForTests() }

        fun tempDir(): File = Files.createTempDirectory("log-sink-registry-").toFile()

        fun File.currentLog(): File = File(this, FileLogSink.FILE_NAME)

        fun File.submittedLines(): List<String> = currentLog().readLines().filterNot { "FileLogSink" in it }

        test("pre-attach lines replay in order ahead of post-attach lines") {
            val dir = tempDir()
            val sink = FileLogSink(directory = Path(dir.absolutePath), dispatcher = Dispatchers.IO)

            listOf("pre 1", "pre 2", "pre 3").forEach(LogSinkRegistry::append)
            LogSinkRegistry.attach(sink)
            LogSinkRegistry.append("post 1")
            sink.close()

            dir.submittedLines() shouldBe listOf("pre 1", "pre 2", "pre 3", "post 1")

            // The started marker sits between the replayed history and live appends.
            val allLines = dir.currentLog().readLines()
            val startedIndex = allLines.indexOfFirst { "app log sink started" in it }
            startedIndex shouldBeGreaterThan allLines.indexOf("pre 3")
            (startedIndex < allLines.indexOf("post 1")).shouldBeTrue()
        }

        test("pre-attach buffer drops oldest beyond its capacity") {
            val dir = tempDir()
            val sink = FileLogSink(directory = Path(dir.absolutePath), dispatcher = Dispatchers.IO)

            repeat(300) { LogSinkRegistry.append("line $it") }
            LogSinkRegistry.attach(sink)
            sink.close()

            // Capacity 256: the oldest 44 of 300 lines are silently discarded.
            dir.submittedLines() shouldBe (44..299).map { "line $it" }
        }

        // The crash path: the process is about to die, so nothing will ever call close(). Every
        // line handed over before the crash has to be on disk by the time flushBlocking returns.
        test("flushBlocking returns once every appended line is on disk, without closing") {
            val dir = tempDir()
            val sink = FileLogSink(directory = Path(dir.absolutePath), dispatcher = Dispatchers.IO)
            LogSinkRegistry.attach(sink)

            repeat(500) { LogSinkRegistry.append("line $it") }
            val flushed = LogSinkRegistry.flushBlocking(5.seconds) { pause -> Thread.sleep(pause.inWholeMilliseconds) }

            flushed.shouldBeTrue()
            dir.submittedLines() shouldBe (0..499).map { "line $it" }
            sink.close()
        }

        test("flushBlocking reports false when no sink is attached to flush into") {
            LogSinkRegistry.append("held in the pre-attach buffer")

            LogSinkRegistry.flushBlocking(50.milliseconds) { pause -> Thread.sleep(pause.inWholeMilliseconds) } shouldBe false
        }
    })
