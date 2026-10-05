package com.calypsan.listenup.server.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RetryAfterTest :
    FunSpec({
        test("a delay in seconds is read as given") {
            retryAfterSeconds("42") shouldBe 42L
            retryAfterSeconds(" 7 ") shouldBe 7L
        }

        test("no header, an HTTP date, or nonsense reads as unknown") {
            retryAfterSeconds(null) shouldBe null
            retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT") shouldBe null
            retryAfterSeconds("-3") shouldBe null
            retryAfterSeconds("") shouldBe null
        }
    })
