package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerReferenceTest :
    FunSpec({
        test("a server fault's reference is the head of its correlation id, the key to its server log line") {
            InternalError(correlationId = "1a2b3c4d-5e6f-7a8b-9c0d").serverReference shouldBe "1a2b3c4d"
        }

        test("a server fault with no correlation id has no reference to show") {
            InternalError().serverReference shouldBe null
            InternalError(correlationId = " ").serverReference shouldBe null
        }

        test("an app-side fault has no server reference — nothing about it is in the server's log") {
            UnexpectedClientError(debugInfo = "IllegalStateException: boom").serverReference shouldBe null
        }

        test("a typed domain failure has no reference even when it carries a correlation id") {
            TransportError.Timeout(correlationId = "1a2b3c4d-5e6f").serverReference shouldBe null
        }
    })
