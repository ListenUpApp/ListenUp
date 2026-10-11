package com.calypsan.listenup.client.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random

/**
 * [fileSinkAtPath] — the iOS backup download's way to stream an archive to a file on disk.
 *
 * A backup can be several gigabytes, so the bytes go to the file as they arrive; the sink is
 * handed to the shared `AdminBackupViewModel.downloadBackup`, which closes it.
 */
class AppleFileSinkTest :
    FunSpec({
        val root = Path(SystemTemporaryDirectory, "apple-file-sink-${Random.nextLong()}")

        beforeSpec { SystemFileSystem.createDirectories(root) }

        fun readBack(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

        test("the bytes written and closed are on disk") {
            val path = Path(root, "backup.tar.gz")
            val content = Random.nextBytes(300_000)

            val sink = fileSinkAtPath(path.toString())
            sink.write(Buffer().apply { write(content) }, content.size.toLong())
            sink.close()

            readBack(path).contentEquals(content) shouldBe true
        }

        test("an existing file is replaced, not appended to") {
            val path = Path(root, "again.tar.gz")
            SystemFileSystem.sink(path).buffered().use { it.write(ByteArray(100) { 1 }) }
            val content = byteArrayOf(7, 8, 9)

            val sink = fileSinkAtPath(path.toString())
            sink.write(Buffer().apply { write(content) }, content.size.toLong())
            sink.close()

            readBack(path).toList() shouldBe content.toList()
        }

        test("a path in a directory that does not exist throws") {
            shouldThrow<IOException> {
                fileSinkAtPath(Path(root, "missing-dir/backup.tar.gz").toString())
            }
        }
    })
