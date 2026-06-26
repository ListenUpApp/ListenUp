@file:OptIn(ExperimentalStdlibApi::class)

package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.security.MessageDigest

class IoPrimitivesTest :
    FunSpec({

        fun tempDir(tag: String): Path {
            val p = Path(SystemTemporaryDirectory, "ioprim-$tag-${tag.hashCode()}")
            deleteRecursively(p)
            SystemFileSystem.createDirectories(p)
            return p
        }

        fun write(
            path: Path,
            bytes: ByteArray,
        ) {
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        }

        test("incremental Sha256 matches JDK MessageDigest over chunked updates") {
            val a = ByteArray(1000) { (it % 7).toByte() }
            val b = ByteArray(500) { (it % 13).toByte() }
            val jdk =
                MessageDigest
                    .getInstance("SHA-256")
                    .apply {
                        update(a)
                        update(b)
                    }.digest()
                    .toHexString()

            val ours =
                Sha256()
                    .apply {
                        update(a)
                        update(b)
                    }.digestHex()

            ours shouldBe jdk
        }

        test("hashSourceSha256 matches JDK over a file's bytes") {
            val dir = tempDir("hsrc")
            val file = Path(dir, "data.bin")
            val bytes = ByteArray(2048) { (it % 251).toByte() }
            write(file, bytes)
            val jdk = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

            val ours = SystemFileSystem.source(file).use { hashSourceSha256(it) }

            ours shouldBe jdk
        }

        test("listRegularFilesRecursively returns files sorted by full path, dirs descended") {
            val dir = tempDir("walk")
            write(Path(dir, "z.txt"), byteArrayOf(1))
            write(Path(dir, "a/c.txt"), byteArrayOf(2))
            write(Path(dir, "a/b/d.txt"), byteArrayOf(3))

            val names = listRegularFilesRecursively(dir).map { it.toString().removePrefix(dir.toString() + "/") }

            names shouldBe listOf("a/b/d.txt", "a/c.txt", "z.txt")
        }

        test("copyDirectoryRecursively deep-copies the tree byte-for-byte") {
            val src = tempDir("copysrc")
            val dst = Path(tempDir("copydst").toString() + "/out")
            write(Path(src, "top.txt"), "top".encodeToByteArray())
            write(Path(src, "nested/inner.txt"), "inner".encodeToByteArray())

            copyDirectoryRecursively(src, dst)

            val top = SystemFileSystem.source(Path(dst, "top.txt")).buffered().use { it.readByteArray() }
            top shouldBe "top".encodeToByteArray()
            val inner = SystemFileSystem.source(Path(dst, "nested/inner.txt")).buffered().use { it.readByteArray() }
            inner shouldBe "inner".encodeToByteArray()
        }

        test("listRegularFilesRecursively and copyDirectoryRecursively are no-ops on absent dirs") {
            val absent = Path(SystemTemporaryDirectory, "does-not-exist-xyz")
            listRegularFilesRecursively(absent) shouldBe emptyList()
            copyDirectoryRecursively(absent, Path(SystemTemporaryDirectory, "dst-xyz")) // must not throw
        }
    })
