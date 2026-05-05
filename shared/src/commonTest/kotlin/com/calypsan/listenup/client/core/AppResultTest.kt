package com.calypsan.listenup.client.core

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [AppResult] — the canonical result type.
 *
 * See Finding 01 D1 for motivation: the codebase previously had three parallel error
 * models ([Result], `AsyncState`, [AppError]) with no conversion path. [AppResult] is
 * the single sealed hierarchy carrying [AppError] directly.
 */
class AppResultTest {
    @Test
    fun successHoldsData() {
        val result: AppResult<String> = AppResult.Success("hello")
        assertIs<AppResult.Success<String>>(result)
        assertEquals("hello", result.data)
    }

    @Test
    fun failureHoldsAppError() {
        val err: AppError = TransportError.NetworkUnavailable(debugInfo = "timeout")
        val result: AppResult<String> = AppResult.Failure(err)
        assertIs<AppResult.Failure>(result)
        assertSame(err, result.error)
    }

    @Test
    fun mapTransformsSuccess() {
        val result: AppResult<Int> = AppResult.Success(5)
        val mapped = result.map { it * 2 }
        assertEquals(AppResult.Success(10), mapped)
    }

    @Test
    fun mapPreservesFailure() {
        val err: AppError = TransportError.NetworkUnavailable()
        val result: AppResult<Int> = AppResult.Failure(err)
        val mapped = result.map { it * 2 }
        assertIs<AppResult.Failure>(mapped)
        assertSame(err, mapped.error)
    }

    @Test
    fun flatMapChainsSuccesses() {
        val result: AppResult<Int> = AppResult.Success(5)
        val chained: AppResult<String> = result.flatMap { AppResult.Success(it.toString()) }
        assertEquals(AppResult.Success("5"), chained)
    }

    @Test
    fun flatMapShortCircuitsOnFailure() {
        val err: AppError = ValidationError(message = "bad")
        val result: AppResult<Int> = AppResult.Failure(err)
        val chained: AppResult<String> = result.flatMap { error("should not be called") }
        assertIs<AppResult.Failure>(chained)
        assertSame(err, chained.error)
    }

    @Test
    fun flatMapSurfacesInnerFailure() {
        val inner: AppError = AuthError.SessionExpired()
        val result: AppResult<Int> = AppResult.Success(5)
        val chained = result.flatMap<Int, String> { AppResult.Failure(inner) }
        assertIs<AppResult.Failure>(chained)
        assertSame(inner, chained.error)
    }

    @Test
    fun foldDispatchesSuccess() {
        val result: AppResult<Int> = AppResult.Success(5)
        val folded = result.fold(onSuccess = { "got $it" }, onFailure = { "err ${it.code}" })
        assertEquals("got 5", folded)
    }

    @Test
    fun foldDispatchesFailure() {
        val result: AppResult<Int> = AppResult.Failure(AuthError.SessionExpired())
        val folded = result.fold(onSuccess = { "got $it" }, onFailure = { "err ${it.code}" })
        assertEquals("err AUTH_SESSION_EXPIRED", folded)
    }

    @Test
    fun getOrNullReturnsDataOnSuccess() {
        val result: AppResult<Int> = AppResult.Success(5)
        assertEquals(5, result.getOrNull())
    }

    @Test
    fun getOrNullReturnsNullOnFailure() {
        val result: AppResult<Int> = AppResult.Failure(TransportError.NetworkUnavailable())
        assertNull(result.getOrNull())
    }

    @Test
    fun errorOrNullReturnsErrorOnFailure() {
        val err: AppError = TransportError.NetworkUnavailable()
        val result: AppResult<Int> = AppResult.Failure(err)
        assertSame(err, result.errorOrNull())
    }

    @Test
    fun errorOrNullReturnsNullOnSuccess() {
        val result: AppResult<Int> = AppResult.Success(5)
        assertNull(result.errorOrNull())
    }

    @Test
    fun onSuccessRunsOnlyOnSuccess() {
        var ran = false
        val result: AppResult<Int> = AppResult.Success(5)
        result.onSuccess { ran = true }
        assertTrue(ran)
    }

    @Test
    fun onFailureRunsOnlyOnFailure() {
        var captured: AppError? = null
        val err: AppError = AuthError.SessionExpired()
        AppResult.Failure(err).onFailure { captured = it }
        assertSame(err, captured)
    }

    @Test
    fun failureFromThrowableMapsViaErrorMapper() {
        val ex = IllegalStateException("boom")
        val failure = Failure(ex)
        // ErrorMapper still emits legacy `UnknownError` for unmapped throwables; the
        // Failure() helper translates that into a unified [ValidationError] so the
        // arbitrary user-facing message (e.g., "boom") survives the bridge — unified
        // [InternalError] has a fixed message. When the legacy hierarchy is deleted in
        // Task 16, ErrorMapper will emit [InternalError] directly and this assertion
        // moves back to InternalError.
        assertIs<ValidationError>(failure.error)
        assertEquals("boom", failure.message)
    }

    @Test
    fun failureFromAppExceptionPreservesTypedError() {
        // The legacy [AppException] still carries a legacy `client.core.error.AppError`;
        // the [Failure] helper translates that to the unified equivalent. Identity isn't
        // preserved across the bridge, but the typed shape (AuthError → SessionExpired)
        // is. Once Task 16 deletes the legacy hierarchy, this test asserts assertSame.
        val originalError = com.calypsan.listenup.client.core.error.AuthError()
        val ex =
            com.calypsan.listenup.client.core.error
                .AppException(originalError)
        val failure = Failure(ex)
        assertIs<AuthError.SessionExpired>(failure.error)
    }

    @Test
    fun validationErrorBuildsValidationErrorFailure() {
        val failure = validationError("bad input")
        assertIs<ValidationError>(failure.error)
        assertEquals("bad input", failure.message)
    }

    @Test
    fun networkErrorHelperBuildsNetworkUnavailableFailure() {
        val failure = networkError("offline")
        assertIs<TransportError.NetworkUnavailable>(failure.error)
    }

    @Test
    fun unauthorizedHelperBuildsSessionExpiredFailure() {
        val failure = unauthorizedError()
        assertIs<AuthError.SessionExpired>(failure.error)
    }
}
