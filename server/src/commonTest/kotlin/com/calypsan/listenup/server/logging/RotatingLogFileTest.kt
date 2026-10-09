package com.calypsan.listenup.server.logging

import com.calypsan.listenup.server.io.deleteRecursively
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random

private const val HEX_RADIX = 16

private fun scratchDirectory(): Path = Path(SystemTemporaryDirectory, "rotating-log-${Random.nextLong().toString(HEX_RADIX)}")

private fun Path.text(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

private fun Path.files(): List<String> = SystemFileSystem.list(this).map { it.name }.sorted()

/**
 * The server's log file: what an operator reads over ssh when `docker logs` can't be trusted. It must keep the
 * newest lines, stay inside a fixed size, and never need the server to stop to rotate.
 */
class RotatingLogFileTest :
    FunSpec({
        test("lines are appended in order, one per line, creating the logs directory on the first write") {
            val logs = Path(scratchDirectory(), "logs")
            val file = RotatingLogFile(logs)

            file.append("first")
            file.append("second")
            file.close()

            Path(logs, "server.log").text() shouldBe "first\nsecond\n"
            logs.parent?.let { deleteRecursively(it) }
        }

        test("a full file rotates: the live file starts again and the older lines move to server.log.1") {
            val logs = scratchDirectory()
            val file = RotatingLogFile(logs, maxBytes = 20, keptFiles = 3)

            file.append("aaaaaaaaaa") // 11 bytes with its newline
            file.append("bbbbbbbbbb") // 22 would pass 20: rotate first
            file.close()

            Path(logs, "server.log").text() shouldBe "bbbbbbbbbb\n"
            Path(logs, "server.log.1").text() shouldBe "aaaaaaaaaa\n"
            deleteRecursively(logs)
        }

        test("only the newest rotated files are kept, so the logs never outgrow their budget") {
            val logs = scratchDirectory()
            val file = RotatingLogFile(logs, maxBytes = 20, keptFiles = 2)

            repeat(6) { file.append("line-$it----") } // 11 bytes each with its newline: every line rotates the last out
            file.close()

            logs.files() shouldContainExactly listOf("server.log", "server.log.1", "server.log.2")
            Path(logs, "server.log").text() shouldBe "line-5----\n"
            Path(logs, "server.log.1").text() shouldBe "line-4----\n"
            Path(logs, "server.log.2").text() shouldBe "line-3----\n"
            logs.files().sumOf { SystemFileSystem.metadataOrNull(Path(logs, it))?.size ?: 0 } shouldBeLessThanOrEqual 60
            deleteRecursively(logs)
        }

        test("a restarted server keeps appending to the existing file and counts its size") {
            val logs = scratchDirectory()
            RotatingLogFile(logs, maxBytes = 20, keptFiles = 2).apply {
                append("aaaaaaaaaa")
                close()
            }

            RotatingLogFile(logs, maxBytes = 20, keptFiles = 2).apply {
                append("bbbbbbbbbb")
                close()
            }

            Path(logs, "server.log").text() shouldBe "bbbbbbbbbb\n"
            Path(logs, "server.log.1").text() shouldBe "aaaaaaaaaa\n"
            deleteRecursively(logs)
        }
    })
