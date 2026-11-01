package com.calypsan.listenup.client.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlin.math.min
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

private const val RESUBSCRIBE_FIRST_DELAY_MS = 1_000L
private const val RESUBSCRIBE_MAX_DELAY_MS = 30_000L

/**
 * Re-subscribe [this] after it fails, with capped backoff, instead of letting one bad read end the flow. For the
 * app-lifetime player pipelines: their `stateIn` would otherwise keep a frozen value for the rest of the process.
 * Logs every failure (the first at ERROR) — honest, not silent. Never place it above a `scan`: a re-subscription
 * restarts everything upstream of it.
 */
internal fun <T> Flow<T>.resubscribeOnFailure(what: String): Flow<T> =
    retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        if (attempt == 0L) {
            logger.error(cause) { "$what failed; re-subscribing" }
        } else {
            logger.warn(cause) { "$what failed again (attempt ${attempt + 1}); re-subscribing" }
        }
        val backoffMs = RESUBSCRIBE_FIRST_DELAY_MS shl min(attempt, MAX_BACKOFF_DOUBLINGS).toInt()
        delay(min(backoffMs, RESUBSCRIBE_MAX_DELAY_MS))
        true
    }

/** 1 s doubled five times is 32 s, already past the cap; stopping there keeps the shift from overflowing. */
private const val MAX_BACKOFF_DOUBLINGS = 5L

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
