@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import platform.posix.F_GETFD
import platform.posix.fcntl
import kotlin.random.Random

/**
 * [fileSourceAtPath] — the iOS upload path's way to stream a picked file from disk.
 *
 * The other iOS construction path, `fileSourceOf`, holds the whole file in memory, which an
 * audiobook cannot afford. This one reads from disk as the request body drains it, and — the part
 * worth a test of its own — **lets go of the file when the read reaches the end**. Ktor's
 * `ByteReadChannel(Source)` only closes its source on `cancel`, so a channel read to completion
 * would otherwise hold its descriptor until the process died: one per track, and a folder of a few
 * hundred tracks would run the app out of descriptors partway through the upload.
 */
class AppleFileSourceTest :
    FunSpec({
        val root = Path(SystemTemporaryDirectory, "apple-file-source-${Random.nextLong()}")

        beforeSpec { SystemFileSystem.createDirectories(root) }

        fun write(
            name: String,
            bytes: ByteArray,
        ): Path {
            val path = Path(root, name)
            SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
            return path
        }

        suspend fun ByteReadChannel.readAll(): ByteArray = readBuffer().readByteArray()

        test("names the file by its last path component and reports its size") {
            val path = write("01 - Opening.m4b", ByteArray(1_234))

            val source = fileSourceAtPath(path.toString())

            source.filename shouldBe "01 - Opening.m4b"
            source.size shouldBe 1_234L
        }

        test("streams the file's exact bytes") {
            runTest {
                val content = Random.nextBytes(300_000)
                val source = fileSourceAtPath(write("track.mp3", content).toString())

                source.openChannel().readAll().contentEquals(content) shouldBe true
            }
        }

        // The upload path retries a failed file by opening it again; each open must start over.
        test("every channel starts again from the beginning") {
            runTest {
                val content = Random.nextBytes(4_096)
                val source = fileSourceAtPath(write("again.mp3", content).toString())

                source.openChannel().readAll().contentEquals(content) shouldBe true
                source.openChannel().readAll().contentEquals(content) shouldBe true
            }
        }

        test("reading many files to the end leaves no descriptor open") {
            runTest {
                val paths = (0 until MANY_FILES).map { write("many-$it.mp3", Random.nextBytes(512)) }
                val before = openDescriptorCount()

                paths.forEach { fileSourceAtPath(it.toString()).openChannel().readAll() }

                openDescriptorCount() shouldBe before
            }
        }

        // A cancelled upload cancels the channel mid-file; that must also let the file go.
        test("cancelling a channel partway through releases its file") {
            runTest {
                val source = fileSourceAtPath(write("partial.mp3", Random.nextBytes(200_000)).toString())
                val before = openDescriptorCount()

                val channel = source.openChannel()
                channel.awaitContent()
                channel.cancel(null)

                openDescriptorCount() shouldBe before
            }
        }
    })

/** More than iOS's default soft limit of 256 descriptors, so a leak would fail loudly, not quietly. */
private const val MANY_FILES = 400

/** How many descriptors this process holds, counted by probing each slot below a generous ceiling. */
private fun openDescriptorCount(): Int = (0 until DESCRIPTOR_PROBE_CEILING).count { fcntl(it, F_GETFD) != -1 }

private const val DESCRIPTOR_PROBE_CEILING = 4_096
