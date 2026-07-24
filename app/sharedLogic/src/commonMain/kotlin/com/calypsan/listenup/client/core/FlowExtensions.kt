package com.calypsan.listenup.client.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Emit a fallback when the upstream flow fails. [recover] receives the error
 * (log here if needed) and returns the value to emit. [CancellationException]
 * is always rethrown so structured concurrency is preserved — this is the
 * invariant the hand-rolled `.catch` blocks across the presentation layer kept
 * getting wrong.
 */
internal inline fun <T> Flow<T>.fallbackTo(crossinline recover: suspend (Throwable) -> T): Flow<T> =
    catch { e ->
        if (e is CancellationException) throw e
        emit(recover(e))
    }
