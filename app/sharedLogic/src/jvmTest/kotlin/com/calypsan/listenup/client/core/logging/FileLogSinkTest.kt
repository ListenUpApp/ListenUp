package com.calypsan.listenup.client.core.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files

/**
 * Behavioural contract of [FileLogSink]: ordered non-corrupt appends, size-based
 * rotation with a two-file cap, drop-oldest overflow, and a draining [FileLogSink.close].
 */
class FileLogSinkTest :
    FunSpec({

        fun tempDir(): File = Files.createTempDirectory("file-log-sink-").toFile()

        fun File.currentLog(): File = File(this, FileLogSink.FILE_NAME)

        fun File.rotatedLog(): File = File(this, FileLogSink.ROTATED_FILE_NAME)

        // Sink-lifecycle marker lines (rotated / dropped) carry the sink's own logger
        // name; filtering them out leaves exactly the caller-submitted lines.
        fun File.submittedLines(): List<String> = readLines().filterNot { "FileLogSink" in it }

        test("appends submitted lines in order and close flushes them") {
            val dir = tempDir()
            val sink = FileLogSink(directory = Path(dir.absolutePath), dispatcher = Dispatchers.IO)

            (1..10).forEach { sink.submit("line $it") }
            sink.close()

            dir.currentLog().submittedLines() shouldBe (1..10).map { "line $it" }
        }

        test("rotates at the size threshold into exactly two files") {
            val dir = tempDir()
            val sink =
                FileLogSink(
                    directory = Path(dir.absolutePath),
                    maxFileBytes = 500,
                    dispatcher = Dispatchers.IO,
                )
            // 100 bytes per line incl. newline; 12 lines force at least two rotations.
            val lines = (0..11).map { i -> "line-%02d ".format(i).padEnd(99, 'x') }

            lines.forEach { sink.submit(it) }
            sink.close()

            dir.rotatedLog().exists().shouldBeTrue()
            dir.currentLog().exists().shouldBeTrue()
            // Cap respected: exactly the two log files, nothing older kept around.
            dir.listFiles()!!.map { it.name }.sorted() shouldBe
                listOf(FileLogSink.FILE_NAME, FileLogSink.ROTATED_FILE_NAME)

            // Rotation preserves order: rotated file holds strictly older lines than current.
            val rotated = dir.rotatedLog().submittedLines()
            val current = dir.currentLog().submittedLines()
            rotated.shouldNotBeEmpty()
            current.shouldNotBeEmpty()
            rotated + current shouldBe lines.takeLast(rotated.size + current.size)
            current.last() shouldBe lines.last()
        }

        test("drops oldest lines on queue overflow and records the dropped count") {
            runTest {
                val dir = tempDir()
                val sink =
                    FileLogSink(
                        directory = Path(dir.absolutePath),
                        queueCapacity = 4,
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )

                // The writer has not been scheduled yet, so all 10 lines hit the queue:
                // capacity 4 means the oldest 6 must be discarded, newest retained.
                repeat(10) { sink.submit("line $it") }
                sink.close()

                dir.currentLog().submittedLines() shouldBe (6..9).map { "line $it" }
                dir
                    .currentLog()
                    .readLines()
                    .any { "dropped 6" in it }
                    .shouldBeTrue()
            }
        }

        test("concurrent submitters never interleave within a line") {
            val dir = tempDir()
            val sink =
                FileLogSink(
                    directory = Path(dir.absolutePath),
                    queueCapacity = 10_000,
                    dispatcher = Dispatchers.IO,
                )
            val payload = "ab".repeat(10)

            withContext(Dispatchers.Default) {
                (1..4)
                    .map { writer ->
                        launch {
                            repeat(250) { i -> sink.submit("writer-$writer line-$i $payload") }
                        }
                    }.joinAll()
            }
            sink.close()

            val lines = dir.currentLog().submittedLines()
            lines.size shouldBe 1000
            lines.all { it.matches(Regex("""writer-\d line-\d+ ($payload)""")) }.shouldBeTrue()
        }

        test("close is idempotent and submit after close is a silent no-op") {
            val dir = tempDir()
            val sink = FileLogSink(directory = Path(dir.absolutePath), dispatcher = Dispatchers.IO)

            sink.submit("before close")
            sink.close()
            sink.submit("after close")
            sink.close()

            dir.currentLog().submittedLines() shouldBe listOf("before close")
        }
    })
