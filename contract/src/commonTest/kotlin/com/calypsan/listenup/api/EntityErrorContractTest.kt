package com.calypsan.listenup.api

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.error.withCorrelationId
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EntityErrorContractTest :
    FunSpec({
        val all: List<EntityError> =
            listOf(
                EntityError.NotFound(),
                EntityError.InvalidParent(),
                EntityError.CycleDetected(),
                EntityError.KindMismatchOnMerge(),
                EntityError.HistoryNotFound(),
            )

        test("every EntityError round-trips polymorphically as AppError") {
            all.forEach { error ->
                val wrapped: AppResult<Unit> = AppResult.Failure(error)
                contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(wrapped)) shouldBe wrapped
            }
        }

        test("every EntityError takes a correlation id and is never retryable") {
            all.forEach { error ->
                (error.withCorrelationId("c1") as AppError).correlationId shouldBe "c1"
                error.isRetryable shouldBe false
            }
        }

        test("codes are stable and distinct") {
            all.map { it.code }.toSet().size shouldBe all.size
        }
    })
