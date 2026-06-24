package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.io.files.Path

class PathBytesTest :
    FunSpec({

        test("writeBytes then readBytes round-trips") {
            val dir = Files.createTempDirectory("listenup-pathbytes-")
            try {
                val file = Path(dir.resolve("b.bin").toString())
                val bytes = byteArrayOf(1, 2, 3, 4, 5)
                file.writeBytes(bytes)
                file.readBytes() shouldBe bytes
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
