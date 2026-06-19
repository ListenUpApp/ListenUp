package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * Verifies [FileSecretStore] generates a strong value, persists it under
 * `$home/secrets.properties`, reuses it across instances, and locks the file down
 * to the owner on POSIX filesystems.
 */
class FileSecretStoreTest :
    FunSpec({

        test("getOrGenerate creates the file and returns a 32+ byte value") {
            val home = Files.createTempDirectory("listenup-secrets-test")
            val secret = FileSecretStore(home).getOrGenerate("jwt.secret")

            secret.toByteArray().size shouldBeGreaterThanOrEqual 32
            Files.exists(home.resolve("secrets.properties")) shouldBe true
        }

        test("a fresh instance returns the same persisted value (generate-once)") {
            val home = Files.createTempDirectory("listenup-secrets-test")
            val first = FileSecretStore(home).getOrGenerate("jwt.secret")
            val second = FileSecretStore(home).getOrGenerate("jwt.secret")

            second shouldBe first
        }

        test("the secrets file is owner read/write only on POSIX") {
            val home = Files.createTempDirectory("listenup-secrets-test")
            FileSecretStore(home).getOrGenerate("jwt.secret")
            val file = home.resolve("secrets.properties")

            val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
            if (posix != null) {
                Files.getPosixFilePermissions(file) shouldBe
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
        }
    })
