package com.calypsan.listenup.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.io.files.Path as IoPath

class BackupManifestTest :
    FunSpec({
        test("manifest round-trips through JSON") {
            val m =
                BackupManifest(
                    formatVersion = 1,
                    serverId = "srv-1",
                    createdAt = 123L,
                    appVersion = "0.1.0",
                    schemaVersion = "29",
                    includesImages = true,
                    checksums = mapOf("db" to "abc", "covers" to "def", "avatars" to "ghi"),
                    bookCount = 10,
                    userCount = 2,
                )
            BackupManifest.fromJson(m.toJson()) shouldBe m
        }

        test("sha256 of a file is stable") {
            val f = Files.createTempFile("chk", ".bin")
            Files.write(f, byteArrayOf(1, 2, 3, 4))
            val a = sha256Of(IoPath(f.toString()))
            val b = sha256Of(IoPath(f.toString()))
            a shouldBe b
            a.length shouldBe 64
        }
    })
