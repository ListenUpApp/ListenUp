package com.calypsan.listenup.client.presentation.error

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ErrorMessagesTest :
    FunSpec({
        test("network unavailable maps to friendly retry copy") {
            userMessageFor(TransportError.NetworkUnavailable()) shouldBe "Can't reach the server. Check your connection."
        }
        test("409 conflict maps to in-use copy") {
            userMessageFor(TransportError.Server4xx(statusCode = 409)) shouldBe "That resource is in use or already exists."
        }
        test("403 forbidden maps to permission copy") {
            userMessageFor(TransportError.Server4xx(statusCode = 403)) shouldBe "You don't have permission to do that."
        }
        test("session expired maps to sign-in prompt") {
            userMessageFor(AuthError.SessionExpired()) shouldBe "Your session expired. Please sign in again."
        }
        test("unmapped subtype falls through to body-level message") {
            val error = TransportError.DataMalformed(detail = "test")
            userMessageFor(error) shouldBe error.message
        }
    })
