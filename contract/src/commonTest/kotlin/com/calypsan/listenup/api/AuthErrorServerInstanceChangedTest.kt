package com.calypsan.listenup.api

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString

class AuthErrorServerInstanceChangedTest :
    FunSpec({
        test("ServerInstanceChanged round-trips as AppError and is non-retryable") {
            val original: AppError = AuthError.ServerInstanceChanged()
            val json = contractJson.encodeToString(original)
            val decoded = contractJson.decodeFromString<AppError>(json)
            decoded.shouldBeInstanceOf<AuthError.ServerInstanceChanged>()
            decoded.isRetryable shouldBe false
            decoded.code shouldBe "AUTH_SERVER_INSTANCE_CHANGED"
        }
    })
