package com.calypsan.listenup.server.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConstantTimeTest :
    FunSpec({
        test("equal strings are equal") {
            constantTimeEquals("s3cret-token", "s3cret-token") shouldBe true
        }

        test("strings differing in the first character are not equal") {
            constantTimeEquals("Xecret-token", "s3cret-token") shouldBe false
        }

        test("strings differing only in the last character are not equal") {
            constantTimeEquals("s3cret-tokeX", "s3cret-token") shouldBe false
        }

        test("strings of different lengths are not equal") {
            constantTimeEquals("s3cret", "s3cret-token") shouldBe false
        }

        test("two empty values are equal") {
            constantTimeEquals("", "") shouldBe true
            constantTimeEquals(ByteArray(0), ByteArray(0)) shouldBe true
        }
    })
