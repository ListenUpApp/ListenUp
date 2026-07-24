package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AdminErrorSerializationTest :
    FunSpec({
        test("every AdminError subtype round-trips as AppError") {
            val errors: List<AdminError> =
                listOf(
                    AdminError.UserNotFound(),
                    AdminError.CannotModifyRoot(),
                    AdminError.CannotDemoteLastAdmin(),
                    AdminError.CannotDeleteSelf(),
                    AdminError.CannotDeleteLastAdmin(),
                    AdminError.InvalidInput(debugInfo = "bad role"),
                )
            errors.forEach { e ->
                val decoded =
                    contractJson.decodeFromString<AppError>(
                        contractJson.encodeToString<AppError>(e),
                    )
                decoded.shouldBeInstanceOf<AdminError>()
                decoded.code shouldBe e.code
                decoded.isRetryable shouldBe false
            }
        }
    })
