package com.calypsan.listenup.server.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.io.IOException

class SeekableSourceTest :
    FunSpec({
        test("reads, seeks, and reports length") {
            val tmp = Files.createTempFile("seekable", ".bin").toFile()
            try {
                tmp.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
                openSeekableSource(tmp.toPath().toKotlinxIoPath()).use { src ->
                    src.length shouldBe 8L
                    src.position() shouldBe 0L
                    src.readFully(2).toList() shouldBe listOf<Byte>(1, 2)
                    src.position() shouldBe 2L
                    src.seek(5)
                    src.readFully(2).toList() shouldBe listOf<Byte>(6, 7)
                }
            } finally {
                tmp.delete()
            }
        }

        test("readFully throws on EOF") {
            val tmp = Files.createTempFile("seekable", ".bin").toFile()
            try {
                tmp.writeBytes(byteArrayOf(1, 2))
                openSeekableSource(tmp.toPath().toKotlinxIoPath()).use { src ->
                    runCatching { src.readFully(4) }.isFailure shouldBe true
                }
            } finally {
                tmp.delete()
            }
        }

        test("readFully past EOF throws a catchable kotlinx.io.IOException") {
            // The parsers catch kotlinx.io.IOException; the JVM source throws EOFException (a subtype)
            // and the native source must too — pin the contract so a short read can't crash a scan.
            val src = ByteArraySeekableSource(byteArrayOf(1, 2, 3, 4))
            shouldThrow<IOException> { src.readFully(8) }
        }
    })

private fun java.nio.file.Path.toKotlinxIoPath(): kotlinx.io.files.Path = kotlinx.io.files.Path(this.toAbsolutePath().toString())
