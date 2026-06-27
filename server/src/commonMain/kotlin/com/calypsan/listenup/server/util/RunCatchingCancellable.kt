package com.calypsan.listenup.server.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching] but rethrows [CancellationException] so structured concurrency
 * is preserved (stdlib [runCatching] catches [Throwable] and would swallow it).
 * Returns [Result.failure] for any other exception. Server-side analog of the
 * client's `suspendRunCatching`.
 */
suspend inline fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
