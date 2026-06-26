package com.calypsan.listenup.server.compression.zip

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private fun writeArchive(entries: List<Triple<String, ZipMethod, ByteArray>>): ByteArray {
    val out = Buffer()
    ZipWriter(out).use { zw ->
        for ((name, method, content) in entries) {
            zw.putEntry(name, method).buffered().use { it.write(Buffer().apply { write(content) }, content.size.toLong()) }
        }
    }
    return out.readByteArray()
}

/** Writes [entries] to a temp file so it can be opened by [ZipFile], which seeks by central-directory offset. */
private fun writeTempArchive(entries: List<Triple<String, ZipMethod, ByteArray>>): Path {
    val archive = writeArchive(entries)
    val path = Path(SystemTemporaryDirectory, "zw-${archive.size}-${entries.hashCode()}.zip")
    SystemFileSystem.sink(path).buffered().use { it.write(Buffer().apply { write(archive) }, archive.size.toLong()) }
    return path
}

private fun readWithJdk(archive: ByteArray): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(archive)).use { zis ->
        while (true) {
            val e = zis.nextEntry ?: break
            result[e.name] = zis.readBytes()
            zis.closeEntry()
        }
    }
    return result
}

class ZipWriterTest :
    FunSpec({
        test("java.util.zip reads our DEFLATE + STORED entries") {
            val entries =
                listOf(
                    Triple("manifest.json", ZipMethod.STORED, """{"v":1}""".encodeToByteArray()),
                    Triple("listenup.db", ZipMethod.DEFLATE, ByteArray(100_000) { (it % 7).toByte() }),
                    Triple("covers/book-1/cover.jpg", ZipMethod.DEFLATE, ByteArray(5000) { (it * 13).toByte() }),
                )
            val read = readWithJdk(writeArchive(entries))
            for ((name, _, content) in entries) read[name] shouldBe content
            read.size shouldBe entries.size
        }

        test("java.util.zip ZipFile reads our archive by name (central directory + seek)") {
            val entries =
                listOf(
                    Triple("manifest.json", ZipMethod.STORED, """{"v":1}""".encodeToByteArray()),
                    Triple("listenup.db", ZipMethod.DEFLATE, ByteArray(120_000) { (it % 7).toByte() }),
                    Triple("covers/book-1/cover.jpg", ZipMethod.DEFLATE, ByteArray(6000) { (it * 13).toByte() }),
                )
            val path = writeTempArchive(entries)
            try {
                ZipFile(File(path.toString())).use { zf ->
                    for ((name, _, content) in entries) {
                        val entry = zf.getEntry(name) ?: error("missing entry $name")
                        zf.getInputStream(entry).use { it.readBytes() } shouldBe content
                    }
                    zf.size() shouldBe entries.size
                }
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }

        test("empty archive (no entries) is a valid zip java.util.zip accepts") {
            readWithJdk(writeArchive(emptyList())).isEmpty() shouldBe true
        }

        test("an empty-content entry round-trips") {
            val read = readWithJdk(writeArchive(listOf(Triple("empty.txt", ZipMethod.DEFLATE, ByteArray(0)))))
            read["empty.txt"] shouldBe ByteArray(0)
        }
    })
