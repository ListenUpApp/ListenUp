package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AppErrorContractTest :
    FunSpec({
        test("every AppError subtype provides message, code, isRetryable, debugInfo") {
            val err: AppError = InternalError(correlationId = "abc-123")
            err.message.isNotBlank() shouldBe true
            err.code.isNotBlank() shouldBe true
            err.isRetryable shouldBe false // InternalError is not retryable by default
            err.debugInfo shouldBe null
            err.correlationId shouldBe "abc-123"
        }

        test("ValidationError carries non-blank presentation fields") {
            val err = ValidationError(message = "title too long", correlationId = null)
            err.message shouldBe "title too long"
            err.code shouldBe "VALIDATION_ERROR"
            err.isRetryable shouldBe false
            err.field shouldBe null // defaulted, wire-backward-compatible
        }

        test("ValidationError.field survives JSON round-trip") {
            val original: AppError = ValidationError(message = "Please enter a valid email address", field = "email")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            val decoded = contractJson.decodeFromString(AppError.serializer(), json)
            decoded.shouldBeInstanceOf<ValidationError>()
            (decoded as ValidationError).field shouldBe "email"
            decoded.message shouldBe "Please enter a valid email address"
        }

        test("InternalError.cause survives JSON round-trip") {
            val original: AppError =
                InternalError(
                    correlationId = "abc-123",
                    debugInfo = "NPE in foo",
                    cause = "NullPointerException",
                )
            val json = contractJson.encodeToString(AppError.serializer(), original)
            val decoded = contractJson.decodeFromString(AppError.serializer(), json)
            decoded.shouldBeInstanceOf<InternalError>()
            (decoded as InternalError).cause shouldBe "NullPointerException"
            decoded.correlationId shouldBe "abc-123"
            decoded.debugInfo shouldBe "NPE in foo"
        }
    })
