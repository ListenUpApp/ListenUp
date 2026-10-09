package com.calypsan.listenup.api

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.error.withCorrelationId
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class WorldEventErrorContractTest :
    FunSpec({
        val all: List<WorldEventError> =
            listOf(
                WorldEventError.NotFound(),
                WorldEventError.HistoryNotFound(),
                WorldEventError.InvalidAnchor(),
                WorldEventError.EntityNotInWorld(),
                WorldEventError.WrongEntityKind(),
            )

        test("every WorldEventError round-trips polymorphically as AppError") {
            all.forEach { error ->
                val wrapped: AppResult<Unit> = AppResult.Failure(error)
                contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(wrapped)) shouldBe wrapped
            }
        }

        test("every WorldEventError takes a correlation id, is never retryable, and speaks a sentence") {
            all.forEach { error ->
                (error.withCorrelationId("c1") as AppError).correlationId shouldBe "c1"
                error.isRetryable shouldBe false
                error.message shouldEndWith "."
            }
        }

        test("codes are stable and distinct") {
            all.map { it.code }.toSet().size shouldBe all.size
        }
    })
