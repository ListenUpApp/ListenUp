package com.calypsan.listenup.core

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.errorOrNull
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.result.fold
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.result.networkError
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.api.result.onSuccess
import com.calypsan.listenup.api.result.unauthorizedError
import com.calypsan.listenup.api.result.validationError
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Tests for [AppResult] — the canonical result type.
 *
 * Motivation: the codebase previously had three parallel error
 * models ([Result], `AsyncState`, [AppError]) with no conversion path. [AppResult] is
 * the single sealed hierarchy carrying [AppError] directly.
 */
class AppResultTest :
    FunSpec({
        test("successHoldsData") {
            val result: AppResult<String> = AppResult.Success("hello")
            result.shouldBeInstanceOf<AppResult.Success<String>>()
            result.data shouldBe "hello"
        }

        test("failureHoldsAppError") {
            val err: AppError = TransportError.NetworkUnavailable(debugInfo = "timeout")
            val result: AppResult<String> = AppResult.Failure(err)
            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error shouldBeSameInstanceAs err
        }

        test("mapTransformsSuccess") {
            val result: AppResult<Int> = AppResult.Success(5)
            val mapped = result.map { it * 2 }
            mapped shouldBe AppResult.Success(10)
        }

        test("mapPreservesFailure") {
            val err: AppError = TransportError.NetworkUnavailable()
            val result: AppResult<Int> = AppResult.Failure(err)
            val mapped = result.map { it * 2 }
            mapped.shouldBeInstanceOf<AppResult.Failure>()
            mapped.error shouldBeSameInstanceAs err
        }

        test("flatMapChainsSuccesses") {
            val result: AppResult<Int> = AppResult.Success(5)
            val chained: AppResult<String> = result.flatMap { AppResult.Success(it.toString()) }
            chained shouldBe AppResult.Success("5")
        }

        test("flatMapShortCircuitsOnFailure") {
            val err: AppError = ValidationError(message = "bad")
            val result: AppResult<Int> = AppResult.Failure(err)
            val chained: AppResult<String> = result.flatMap { error("should not be called") }
            chained.shouldBeInstanceOf<AppResult.Failure>()
            chained.error shouldBeSameInstanceAs err
        }

        test("flatMapSurfacesInnerFailure") {
            val inner: AppError = AuthError.SessionExpired()
            val result: AppResult<Int> = AppResult.Success(5)
            val chained = result.flatMap<Int, String> { AppResult.Failure(inner) }
            chained.shouldBeInstanceOf<AppResult.Failure>()
            chained.error shouldBeSameInstanceAs inner
        }

        test("foldDispatchesSuccess") {
            val result: AppResult<Int> = AppResult.Success(5)
            val folded = result.fold(onSuccess = { "got $it" }, onFailure = { "err ${it.code}" })
            folded shouldBe "got 5"
        }

        test("foldDispatchesFailure") {
            val result: AppResult<Int> = AppResult.Failure(AuthError.SessionExpired())
            val folded = result.fold(onSuccess = { "got $it" }, onFailure = { "err ${it.code}" })
            folded shouldBe "err AUTH_SESSION_EXPIRED"
        }

        test("getOrNullReturnsDataOnSuccess") {
            val result: AppResult<Int> = AppResult.Success(5)
            result.getOrNull() shouldBe 5
        }

        test("getOrNullReturnsNullOnFailure") {
            val result: AppResult<Int> = AppResult.Failure(TransportError.NetworkUnavailable())
            result.getOrNull() shouldBe null
        }

        test("errorOrNullReturnsErrorOnFailure") {
            val err: AppError = TransportError.NetworkUnavailable()
            val result: AppResult<Int> = AppResult.Failure(err)
            result.errorOrNull() shouldBeSameInstanceAs err
        }

        test("errorOrNullReturnsNullOnSuccess") {
            val result: AppResult<Int> = AppResult.Success(5)
            result.errorOrNull() shouldBe null
        }

        test("onSuccessRunsOnlyOnSuccess") {
            var ran = false
            val result: AppResult<Int> = AppResult.Success(5)
            result.onSuccess { ran = true }
            ran shouldBe true
        }

        test("onFailureRunsOnlyOnFailure") {
            var captured: AppError? = null
            val err: AppError = AuthError.SessionExpired()
            AppResult.Failure(err).onFailure { captured = it }
            captured shouldBeSameInstanceAs err
        }

        test("failureFromThrowableMapsViaErrorMapper") {
            val ex = IllegalStateException("boom")
            val failure = Failure(ex)
            val internal = failure.error.shouldBeInstanceOf<InternalError>()
            (internal.debugInfo?.contains("boom") == true) shouldBe true
            (internal.debugInfo?.contains("IllegalStateException") == true) shouldBe true
        }

        test("validationErrorBuildsValidationErrorFailure") {
            val failure = validationError("bad input")
            failure.error.shouldBeInstanceOf<ValidationError>()
            failure.message shouldBe "bad input"
        }

        test("networkErrorHelperBuildsNetworkUnavailableFailure") {
            val failure = networkError("offline")
            failure.error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
        }

        test("unauthorizedHelperBuildsSessionExpiredFailure") {
            val failure = unauthorizedError()
            failure.error.shouldBeInstanceOf<AuthError.SessionExpired>()
        }
    })
