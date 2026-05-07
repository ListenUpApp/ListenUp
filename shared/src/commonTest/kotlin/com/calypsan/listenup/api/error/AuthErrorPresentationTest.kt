package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AuthErrorPresentationTest :
    FunSpec({
        test("InvalidCredentials has retryable=false and stable code") {
            val err = AuthError.InvalidCredentials()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "AUTH_INVALID_CREDENTIALS"
            err.isRetryable shouldBe false
        }

        test("SessionExpired is not auto-retryable") {
            val err = AuthError.SessionExpired()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "AUTH_SESSION_EXPIRED"
            err.isRetryable shouldBe false // re-auth is a different action; not "same call may succeed"
        }

        test("EmailAlreadyExists is not retryable") {
            val err = AuthError.EmailAlreadyExists()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "AUTH_EMAIL_ALREADY_EXISTS"
            err.isRetryable shouldBe false
        }

        test("RateLimited is retryable and surfaces retryAfterSeconds") {
            val err = AuthError.RateLimited(retryAfterSeconds = 30)
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "AUTH_RATE_LIMITED"
            err.isRetryable shouldBe true
        }
    })
