package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PasswordPolicyTest :
    FunSpec({
        test("blank or whitespace-only password fails with BLANK") {
            val blank = PasswordPolicy.validate("        ").shouldBeInstanceOf<AppResult.Failure>()
            blank.error.shouldBeInstanceOf<AuthError.WeakPassword>().reason shouldBe WeakPasswordReason.BLANK

            val empty = PasswordPolicy.validate("").shouldBeInstanceOf<AppResult.Failure>()
            empty.error.shouldBeInstanceOf<AuthError.WeakPassword>().reason shouldBe WeakPasswordReason.BLANK
        }

        test("password shorter than the minimum fails with TOO_SHORT") {
            val result = PasswordPolicy.validate("x".repeat(7)).shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AuthError.WeakPassword>().reason shouldBe WeakPasswordReason.TOO_SHORT
        }

        test("password longer than the maximum fails with TOO_LONG") {
            val result = PasswordPolicy.validate("x".repeat(1025)).shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AuthError.WeakPassword>().reason shouldBe WeakPasswordReason.TOO_LONG
        }

        test("password at the exact minimum and maximum boundaries succeeds") {
            PasswordPolicy.validate("x".repeat(8)) shouldBe AppResult.Success(Unit)
            PasswordPolicy.validate("x".repeat(1024)) shouldBe AppResult.Success(Unit)
        }

        test("an ordinary valid password succeeds") {
            PasswordPolicy.validate("correct horse battery staple") shouldBe AppResult.Success(Unit)
        }
    })
