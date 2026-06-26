package com.calypsan.listenup.server.compression.zip

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.io.File

private fun writeOurs(entries: List<Triple<String, ZipMethod, ByteArray>>): ByteArray {
    val out = Buffer()
    ZipWriter(out).use { zw ->
        for ((name, method, content) in entries) {
            zw.putEntry(name, method).buffered().use {
                it.write(Buffer().apply { write(content) }, content.size.toLong())
            }
        }
    }
    return out.readByteArray()
}

private fun temp(
    bytes: ByteArray,
    tag: String,
): Path {
    val p = Path(SystemTemporaryDirectory, "zrt-$tag-${bytes.size}.zip")
    SystemFileSystem.sink(p).buffered().use { it.write(bytes) }
    return p
}

class ZipRoundTripTest :
    FunSpec({
        test("ours to ours round-trips a varied entry set (STORED + DEFLATE, nested, empty)") {
            val entries =
                listOf(
                    Triple("manifest.json", ZipMethod.STORED, """{"v":1,"n":"x"}""".encodeToByteArray()),
                    Triple("listenup.db", ZipMethod.DEFLATE, ByteArray(250_000) { (it % 9).toByte() }),
                    Triple("covers/a/1.jpg", ZipMethod.DEFLATE, ByteArray(3333) { (it * 7).toByte() }),
                    Triple("covers/b/2.png", ZipMethod.STORED, ByteArray(1500) { (it * 3).toByte() }),
                    Triple("empty.bin", ZipMethod.DEFLATE, ByteArray(0)),
                )
            val p = temp(writeOurs(entries), "varied")
            try {
                ZipReader(p).use { zr ->
                    zr.entries().map { it.name }.toSet() shouldBe entries.map { it.first }.toSet()
                    for ((name, _, content) in entries) {
                        zr.openEntry(zr.entry(name)!!).buffered().readByteArray() shouldBe content
                    }
                }
            } finally {
                SystemFileSystem.delete(p, mustExist = false)
            }
        }

        test("ZIP64: more than 65535 entries forces the ZIP64 EOCD; our reader and java.util.zip both read all") {
            val out = Buffer()
            ZipWriter(out).use { zw ->
                repeat(70_000) { i ->
                    zw.putEntry("e$i", ZipMethod.STORED).buffered().use {
                        it.write(Buffer().apply { write(byteArrayOf(i.toByte())) }, 1)
                    }
                }
            }
            val p = temp(out.readByteArray(), "zip64")
            try {
                ZipReader(p).use { zr ->
                    zr.entries().size shouldBe 70_000
                    zr.openEntry(zr.entry("e69999")!!).buffered().readByteArray() shouldBe byteArrayOf(69_999.toByte())
                }
                java.util.zip
                    .ZipFile(File(p.toString()))
                    .use { it.size() shouldBe 70_000 }
            } finally {
                SystemFileSystem.delete(p, mustExist = false)
            }
        }

        test("realistic backup shape round-trips through java.util.zip and ours") {
            val entries =
                buildList {
                    add(Triple("manifest.json", ZipMethod.STORED, """{"v":1}""".encodeToByteArray()))
                    add(Triple("listenup.db", ZipMethod.DEFLATE, ByteArray(500_000) { (it % 11).toByte() }))
                    repeat(50) { i ->
                        add(Triple("covers/b$i/cover.jpg", ZipMethod.DEFLATE, ByteArray(2000) { (it * (i + 1)).toByte() }))
                    }
                    repeat(20) { i ->
                        add(Triple("avatars/u$i.png", ZipMethod.STORED, ByteArray(800) { (it + i).toByte() }))
                    }
                }
            val p = temp(writeOurs(entries), "backup")
            try {
                java.util.zip.ZipFile(File(p.toString())).use { jdk ->
                    jdk.size() shouldBe entries.size
                    for ((name, _, content) in entries) {
                        jdk.getInputStream(jdk.getEntry(name)).use { it.readBytes() } shouldBe content
                    }
                }
                ZipReader(p).use { zr ->
                    for ((name, _, content) in entries) {
                        zr.openEntry(zr.entry(name)!!).buffered().readByteArray() shouldBe content
                    }
                }
            } finally {
                SystemFileSystem.delete(p, mustExist = false)
            }
        }
    })
