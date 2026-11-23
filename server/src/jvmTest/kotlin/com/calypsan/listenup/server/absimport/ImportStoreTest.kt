package com.calypsan.listenup.server.absimport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlinx.io.files.Path as IoPath

class ImportStoreTest :
    FunSpec({
        test("the boot sweep removes an upload a crash left in the temp directory, and nothing else") {
            runTest {
                val home = Files.createTempDirectory("abs-sweep-")
                val paths = ImportPaths(IoPath(home.toString())).apply { ensureDirs() }
                val tmp =
                    java.nio.file.Path
                        .of(paths.tmpDir.toString())
                val leftover = Files.write(tmp.resolve("abs-upload-1.audiobookshelf"), ByteArray(16))
                val staged =
                    java.nio.file.Path
                        .of(paths.dirFor("abs-1").toString())
                Files.createDirectories(staged)
                val kept = Files.write(staged.resolve("analysis.json"), "{}".toByteArray())

                ImportStore(paths).clearUploadLeftovers()

                Files.exists(leftover) shouldBe false
                Files.exists(tmp) shouldBe true
                Files.exists(kept) shouldBe true
            }
        }
    })
