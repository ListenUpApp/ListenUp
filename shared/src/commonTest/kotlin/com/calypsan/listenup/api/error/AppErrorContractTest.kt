package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppErrorContractTest : FunSpec({
    test("every AppError subtype provides message, code, isRetryable, debugInfo") {
        val err: AppError = InternalError(correlationId = "abc-123")
        err.message.isNotBlank() shouldBe true
        err.code.isNotBlank() shouldBe true
        err.isRetryable shouldBe false  // InternalError is not retryable by default
        err.debugInfo shouldBe null
        err.correlationId shouldBe "abc-123"
    }

    test("ValidationError carries non-blank presentation fields") {
        val err = ValidationError(message = "title too long", correlationId = null)
        err.message shouldBe "title too long"
        err.code shouldBe "VALIDATION_ERROR"
        err.isRetryable shouldBe false
    }
})
