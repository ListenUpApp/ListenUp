package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AuthErrorPresentationTest : FunSpec({
    test("InvalidCredentials has retryable=false and stable code") {
        val err = AuthError.InvalidCredentials()
        err.message.isNotBlank() shouldBe true
        err.code shouldBe "AUTH_INVALID_CREDENTIALS"
        err.isRetryable shouldBe false
    }

    test("SessionExpired is retryable") {
        val err = AuthError.SessionExpired()
        err.code shouldBe "AUTH_SESSION_EXPIRED"
        err.isRetryable shouldBe true   // user can retry by re-authenticating
    }

    test("EmailAlreadyExists is not retryable") {
        val err = AuthError.EmailAlreadyExists()
        err.code shouldBe "AUTH_EMAIL_ALREADY_EXISTS"
        err.isRetryable shouldBe false
    }
})
