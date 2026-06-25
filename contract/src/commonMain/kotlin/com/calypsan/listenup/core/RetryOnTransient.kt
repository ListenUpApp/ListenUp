package com.calypsan.listenup.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block], retrying up to [maxAttempts] times when it fails with a *transient* error — one for
 * which [isTransient] returns true.
 *
 * A non-transient failure propagates immediately without retrying; a [CancellationException] is always
 * re-thrown without being retried or classified; and when every attempt fails transiently the last
 * transient error is re-thrown. [onRetry] runs between attempts (e.g. to back off), receiving the
 * zero-based index of the attempt that just failed and its error.
 *
 * The broad `catch` is intentional: a retry helper cannot know which exception types its [block] may
 * raise, so it catches everything and delegates the keep-or-propagate decision to [isTransient].
 */
internal suspend fun <T> retryOnTransient(
    maxAttempts: Int,
    isTransient: (Throwable) -> Boolean,
    onRetry: suspend (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend () -> T,
): T {
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Propagate a non-transient failure immediately, and a transient one once the final
            // attempt is spent; otherwise back off and retry.
            if (!isTransient(e) || attempt == maxAttempts - 1) throw e
            onRetry(attempt, e)
        }
    }
    error("retryOnTransient requires maxAttempts >= 1, was $maxAttempts")
}
