package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class InviteErrorSerializationTest :
    FunSpec({
        test("every InviteError subtype round-trips as AppError") {
            val errors: List<InviteError> =
                listOf(
                    InviteError.NotFound(),
                    InviteError.Expired(),
                    InviteError.AlreadyClaimed(),
                    InviteError.EmailInUse(),
                    InviteError.InvalidInput(debugInfo = "bad email"),
                )
            errors.forEach { e ->
                val decoded =
                    contractJson.decodeFromString<AppError>(
                        contractJson.encodeToString<AppError>(e),
                    )
                decoded.shouldBeInstanceOf<InviteError>()
                decoded.code shouldBe e.code
                decoded.isRetryable shouldBe false
            }
        }
    })
