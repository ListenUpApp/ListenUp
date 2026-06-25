package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

class FileTextWriteTest :
    FunSpec({
        test("writeText then readText round-trips the exact content") {
            val file = createTempFileIn(SystemTemporaryDirectory, "filetext-", ".txt")
            try {
                val content = "line one\nline two — üñîçödé\n"
                file.writeText(content)
                file.readText() shouldBe content
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
            }
        }

        test("writeText truncates an existing file (no append)") {
            val file = createTempFileIn(SystemTemporaryDirectory, "filetext-", ".txt")
            try {
                file.writeText("the quick brown fox jumped")
                file.writeText("short")
                file.readText() shouldBe "short"
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
            }
        }
    })
