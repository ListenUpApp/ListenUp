package com.calypsan.listenup.client.core

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pins the non-RPC result boundaries to the same shape as the RPC one: every throwable but a
 * cancellation becomes a typed failure. A browser's native `RangeError` is a [Throwable] and not an
 * [Exception], and it used to escape these boundaries and kill the caller's coroutine.
 */
class ResultCatchingTest :
    FunSpec({
        test("suspendRunCatching folds a throwable that is not an Exception into a failure") {
            val result = suspendRunCatching<Int> { throw NotAnException() }

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<UnexpectedClientError>()
        }

        test("suspendRunCatching still re-throws cancellation") {
            shouldThrow<CancellationException> { suspendRunCatching<Int> { throw CancellationException("stop") } }
        }

        test("suspendRunCatching passes a value through") {
            suspendRunCatching { 7 } shouldBe AppResult.Success(7)
        }

        test("pullCatching folds a throwable that is not an Exception into a failure") {
            val result = pullCatching<Int> { throw NotAnException() }

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<UnexpectedClientError>()
        }

        test("pullCatching still re-throws cancellation") {
            shouldThrow<CancellationException> { pullCatching<Int> { throw CancellationException("stop") } }
        }
    })

/** Stands in for a JS-native error: a [Throwable] that is not an [Exception] on every platform. */
private class NotAnException : Error("not an Exception")
