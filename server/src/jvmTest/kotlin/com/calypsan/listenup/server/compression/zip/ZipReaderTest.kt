package com.calypsan.listenup.server.compression.zip

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
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

/** Patches a 4-byte little-endian unsigned int at [offsetFromStart] — used to corrupt EOCD fields. */
private fun patchU32FromEnd(
    bytes: ByteArray,
    offsetFromStart: Int,
    value: Long,
) {
    bytes[offsetFromStart] = (value and 0xFF).toByte()
    bytes[offsetFromStart + 1] = ((value shr 8) and 0xFF).toByte()
    bytes[offsetFromStart + 2] = ((value shr 16) and 0xFF).toByte()
    bytes[offsetFromStart + 3] = ((value shr 24) and 0xFF).toByte()
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

        test("a central directory size implausible for the entry count is rejected without buffering the file") {
            // One entry, but the EOCD claims the directory spans the whole file. The totalEntries↔cdSize
            // cross-check must reject this in O(1) — never allocate the file onto the heap. The content is
            // large enough (> ~192 KB, one entry's maximum plausible header) that only the cross-check,
            // not the range guard, can fire; it is still modest enough that a regression couldn't OOM.
            val bytes = jdkZip(listOf(Triple("big.bin", ZipEntry.STORED, ByteArray(300_000))))
            val fileLen = bytes.size.toLong()
            patchU32FromEnd(bytes, bytes.size - 22 + 12, fileLen) // cdSize := fileLen
            patchU32FromEnd(bytes, bytes.size - 22 + 16, 0L) // cdOffset := 0
            val path = writeTemp(bytes)
            try {
                shouldThrow<MalformedZipException> { ZipReader(path) }
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }

        test("a central directory that does not abut the EOCD record is rejected (anchor mismatch)") {
            // A valid 2-entry archive whose EOCD cdOffset is redirected to the start of entry data. The
            // self-consistency anchor (cdOffset + cdSize must equal the EOCD offset) rejects the redirect.
            val bytes =
                jdkZip(
                    listOf(
                        Triple("one.txt", ZipEntry.STORED, "first".encodeToByteArray()),
                        Triple("two.txt", ZipEntry.STORED, "second".encodeToByteArray()),
                    ),
                )
            patchU32FromEnd(bytes, bytes.size - 22 + 16, 0L) // cdOffset := 0 (points into entry data)
            val path = writeTemp(bytes)
            try {
                shouldThrow<MalformedZipException> { ZipReader(path) }
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }

        test("openEntry on a file truncated after construction throws MalformedZipException, not EOFException") {
            // The directory parses cleanly, then the file is truncated (disk rot / partial write) before the
            // entry is opened. openEntry must re-check the live length and surface a typed exception.
            val path = writeTemp(jdkZip(listOf(Triple("a.txt", ZipEntry.STORED, "hello".encodeToByteArray()))))
            try {
                ZipReader(path).use { zr ->
                    val entry = zr.entry("a.txt")!!
                    RandomAccessFile(path.toString(), "rw").use { it.setLength(8) }
                    shouldThrow<MalformedZipException> { zr.openEntry(entry) }
                }
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }
    })
