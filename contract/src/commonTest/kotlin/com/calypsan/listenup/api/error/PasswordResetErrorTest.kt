package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PasswordResetErrorTest :
    FunSpec({
        val json = contractJson

        test("every reset error is non-retryable — all of them need user action") {
            listOf(
                AuthError.ResetRequestNotFound(),
                AuthError.ResetNotApproved(),
                AuthError.ResetCodeIncorrect(attemptsRemaining = 3),
                AuthError.ResetAttemptsExhausted(),
                AuthError.RootResetUnavailable(),
            ).forEach { it.isRetryable shouldBe false }
        }

        test("reset errors round-trip as AuthError") {
            val original: AuthError = AuthError.ResetCodeIncorrect(attemptsRemaining = 2)
            json.decodeFromString<AuthError>(json.encodeToString(original)) shouldBe original
        }

        test("RootResetUnavailable message reveals nothing about the instance state") {
            AuthError.RootResetUnavailable().message shouldBe
                "That reset token is not valid. Check the server log and try again."
        }

        test("stamping a correlation id preserves the remaining-attempts count") {
            val stamped = AuthError.ResetCodeIncorrect(attemptsRemaining = 3).withCorrelationId("corr-1")

            stamped.shouldBeInstanceOf<AuthError.ResetCodeIncorrect>()
            stamped.attemptsRemaining shouldBe 3
            stamped.correlationId shouldBe "corr-1"
        }
    })
