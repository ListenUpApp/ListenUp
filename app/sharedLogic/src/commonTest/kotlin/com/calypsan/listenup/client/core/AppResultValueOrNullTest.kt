package com.calypsan.listenup.client.core

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.valueOrNull
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class AppResultValueOrNullTest :
    FunSpec({
        test("Success returns its data and does not invoke onFailure") {
            var failureSeen = false
            AppResult.Success(42).valueOrNull { failureSeen = true } shouldBe 42
            failureSeen shouldBe false
        }

        test("Failure returns null and invokes onFailure with the typed error") {
            val err: AppError = TransportError.NetworkUnavailable(debugInfo = "x")
            var seen: AppError? = null
            AppResult.Failure(err).valueOrNull { seen = it }.shouldBeNull()
            seen shouldBe err
        }
    })
