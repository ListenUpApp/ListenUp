package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

class CreationTimeTest :
    FunSpec({
        test("creationTimeMillis is non-negative for an existing file") {
            val file = createTempFileIn(SystemTemporaryDirectory, "ctime-", ".txt")
            try {
                // Native always returns 0L (posix birth time unavailable); JVM returns a real epoch-ms time > 0.
                (creationTimeMillis(file) >= 0L) shouldBe true
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
            }
        }
    })
