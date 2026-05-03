package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

class PasswordHasherTest :
    FunSpec({
        val hasher = PasswordHasher()

        test("hash produces a PHC argon2id string") {
            val phc = hasher.hash("correcthorsebatterystaple")
            phc shouldStartWith "$" + "argon2id"
        }

        test("verify accepts the right password and rejects the wrong one") {
            val phc = hasher.hash("hunter22hunter22")
            hasher.verify("hunter22hunter22", phc) shouldBe true
            hasher.verify("wrong-password!!", phc) shouldBe false
        }

        test("identical plaintexts produce distinct hashes (random salt)") {
            // Locks in the salt-randomness contract — protects against accidental
            // removal of addRandomSalt or replacement with a fixed salt.
            hasher.hash("same-password") shouldNotBe hasher.hash("same-password")
        }
    })
