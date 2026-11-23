package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * An empty password is a wrong password, on every server build: it never verifies, not even against
 * a hash of itself, and hashing one still returns a well-formed hash rather than throwing. Nothing upstream guarantees the
 * hasher never sees one: a profile password change carries the current password unvalidated, so an
 * empty one reaches [PasswordHasher.verify] and must read as a mismatch — the caller then answers
 * with its typed wrong-password error — rather than escaping as an exception from the native
 * libargon2 binding. As `commonTest` this runs against both actuals: password4j on the JVM and
 * libargon2 on linuxX64.
 */
class PasswordHasherEmptyInputTest :
    FunSpec({
        val hasher = PasswordHasher()

        test("verifying an empty password against a real hash fails") {
            val phc = hasher.hash("a-real-password")

            hasher.verify("", phc) shouldBe false
        }

        test("an empty password hashes without throwing, and never verifies, even against its own hash") {
            val phc = hasher.hash("")

            phc shouldStartWith "$" + "argon2id"
            hasher.verify("", phc) shouldBe false
            hasher.verify("not-empty", phc) shouldBe false
        }
    })
