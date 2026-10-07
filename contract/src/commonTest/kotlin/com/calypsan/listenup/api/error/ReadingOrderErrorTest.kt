package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class ReadingOrderErrorTest :
    FunSpec({
        val all: List<ReadingOrderError> =
            listOf(
                ReadingOrderError.NotFound(),
                ReadingOrderError.Forbidden(),
                ReadingOrderError.InvalidName(),
                ReadingOrderError.NameAlreadyExists(),
                ReadingOrderError.BookOutsideSeries(),
                ReadingOrderError.ChoiceUnavailable(),
                ReadingOrderError.InvalidInput(),
            )

        test("every subtype round-trips as an AppError, is not retryable, and reads as a sentence") {
            all.forEach { error ->
                contractJson.decodeFromString<AppError>(contractJson.encodeToString<AppError>(error)) shouldBe error
                error.isRetryable shouldBe false
                error.message shouldEndWith "."
            }
        }

        test("every subtype has its own stable code") {
            all.map { it.code }.toSet().size shouldBe all.size
        }

        test("correlation stamping reaches every subtype") {
            all.forEach { (it.withCorrelationId("c-1") as ReadingOrderError).correlationId shouldBe "c-1" }
        }
    })
