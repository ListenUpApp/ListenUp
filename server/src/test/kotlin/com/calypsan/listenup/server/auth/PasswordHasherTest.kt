package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest

class PasswordHasherTest :
    FunSpec({
        val hasher = PasswordHasher()

        test("hash produces a PHC argon2id string") {
            runTest {
                val phc = hasher.hash("correcthorsebatterystaple")
                phc shouldStartWith "$" + "argon2id"
            }
        }

        test("verify accepts the right password and rejects the wrong one") {
            runTest {
                val phc = hasher.hash("hunter22hunter22")
                hasher.verify("hunter22hunter22", phc) shouldBe true
                hasher.verify("wrong-password!!", phc) shouldBe false
            }
        }
    })
