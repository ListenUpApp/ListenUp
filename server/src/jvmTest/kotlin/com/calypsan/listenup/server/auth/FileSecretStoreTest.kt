package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlinx.io.files.Path

/**
 * Verifies [FileSecretStore] generates a strong value, persists it under
 * `$home/secrets.properties`, reuses it across instances, and locks the file down
 * to the owner on POSIX filesystems.
 */
class FileSecretStoreTest :
    FunSpec({

        test("getOrGenerate creates the file and returns a 32+ byte value") {
            val home = Files.createTempDirectory("listenup-secrets-test")
            val secret = FileSecretStore(Path(home.toString())).getOrGenerate("jwt.secret")

            secret.toByteArray().size shouldBeGreaterThanOrEqual 32
            Files.exists(home.resolve("secrets.properties")) shouldBe true
        }

        test("a fresh instance returns the same persisted value (generate-once)") {
            val home = Files.createTempDirectory("listenup-secrets-test")
            val first = FileSecretStore(Path(home.toString())).getOrGenerate("jwt.secret")
            val second = FileSecretStore(Path(home.toString())).getOrGenerate("jwt.secret")

            second shouldBe first
        }

        test("the secrets file is owner read/write only on POSIX") {
            val home = Files.createTempDirectory("listenup-secrets-test")
            FileSecretStore(Path(home.toString())).getOrGenerate("jwt.secret")
            val file = home.resolve("secrets.properties")

            val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
            if (posix != null) {
                Files.getPosixFilePermissions(file) shouldBe
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
        }

        test("reads a secrets file written by java.util.Properties") {
            val home = Files.createTempDirectory("listenup-secrets-interop")
            val legacy = java.util.Properties()
            legacy.setProperty("jwt.secret", "abc-DEF_ghi123")
            Files.newOutputStream(home.resolve("secrets.properties")).use {
                legacy.store(it, "legacy header")
            }

            FileSecretStore(Path(home.toString())).getOrGenerate("jwt.secret") shouldBe "abc-DEF_ghi123"
        }

        test("a file we write is readable by java.util.Properties") {
            val home = Files.createTempDirectory("listenup-secrets-roundtrip")
            val secret = FileSecretStore(Path(home.toString())).getOrGenerate("jwt.secret")

            val readBack = java.util.Properties()
            Files.newInputStream(home.resolve("secrets.properties")).use { readBack.load(it) }
            readBack.getProperty("jwt.secret") shouldBe secret
        }
    })
