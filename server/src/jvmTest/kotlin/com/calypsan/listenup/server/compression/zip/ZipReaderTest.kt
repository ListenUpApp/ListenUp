package com.calypsan.listenup.server.compression.zip

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun jdkZip(entries: List<Triple<String, Int, ByteArray>>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zos ->
        for ((name, method, content) in entries) {
            val e = ZipEntry(name)
            e.method = method
            if (method == ZipEntry.STORED) {
                e.size = content.size.toLong()
                val crc = java.util.zip.CRC32()
                crc.update(content)
                e.crc = crc.value
                e.compressedSize = content.size.toLong()
            }
            zos.putNextEntry(e)
            zos.write(content)
            zos.closeEntry()
        }
    }
    return bos.toByteArray()
}

private fun writeTemp(bytes: ByteArray): Path {
    val p = Path(SystemTemporaryDirectory, "zr-${bytes.size}-${bytes.contentHashCode()}.zip")
    SystemFileSystem.sink(p).buffered().use { it.write(bytes) }
    return p
}

class ZipReaderTest :
    FunSpec({
        test("reads java.util.zip archives by name (DEFLATE + STORED + nested)") {
            val entries =
                listOf(
                    Triple("manifest.json", ZipEntry.STORED, """{"v":1}""".encodeToByteArray()),
                    Triple("listenup.db", ZipEntry.DEFLATED, ByteArray(80_000) { (it % 5).toByte() }),
                    Triple("covers/a/b.jpg", ZipEntry.DEFLATED, ByteArray(4000) { (it * 7).toByte() }),
                )
            val path = writeTemp(jdkZip(entries))
            try {
                ZipReader(path).use { zr ->
                    zr.entries().map { it.name }.toSet() shouldBe entries.map { it.first }.toSet()
                    for ((name, _, content) in entries) {
                        zr.openEntry(zr.entry(name)!!).buffered().readByteArray() shouldBe content
                    }
                    zr.entry("nope") shouldBe null
                }
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }

        test("a truncated archive throws MalformedZipException") {
            val good = jdkZip(listOf(Triple("x", ZipEntry.DEFLATED, ByteArray(5000) { it.toByte() })))
            val path = writeTemp(good.copyOf(good.size / 2))
            try {
                io.kotest.assertions.throwables
                    .shouldThrow<MalformedZipException> { ZipReader(path) }
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }

        test("an archive with a 64KiB comment (EOCD pushed back) still parses") {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                zos.setComment("x".repeat(40_000))
                zos.putNextEntry(ZipEntry("a"))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
            val path = writeTemp(bos.toByteArray())
            try {
                ZipReader(path).use { zr ->
                    zr.openEntry(zr.entry("a")!!).buffered().readByteArray() shouldBe byteArrayOf(1, 2, 3)
                }
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }
    })
