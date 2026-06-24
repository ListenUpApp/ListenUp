package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.io.files.Path

class Sha256FileTest :
    FunSpec({

        test("hashFileSha256 streams a file to the same digest as its bytes") {
            val f = Files.createTempFile("listenup-sha256-", ".txt")
            try {
                Files.write(f, "abc".encodeToByteArray())
                hashFileSha256(Path(f.toString())) shouldBe hashBytesSha256("abc".encodeToByteArray())
            } finally {
                Files.deleteIfExists(f)
            }
        }
    })
