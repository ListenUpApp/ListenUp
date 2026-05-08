package com.calypsan.listenup.server.embeddedmeta

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SeekableAudioSourceTest :
    FunSpec({
        test("reads, seeks, and reports length") {
            val tmp = Files.createTempFile("seekable", ".bin").toFile()
            try {
                tmp.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
                defaultSeekableSource(tmp.toPath().toKotlinxIoPath()).use { src ->
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
                defaultSeekableSource(tmp.toPath().toKotlinxIoPath()).use { src ->
                    runCatching { src.readFully(4) }.isFailure shouldBe true
                }
            } finally {
                tmp.delete()
            }
        }
    })

private fun java.nio.file.Path.toKotlinxIoPath(): kotlinx.io.files.Path = kotlinx.io.files.Path(this.toAbsolutePath().toString())
