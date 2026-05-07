package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.AppResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Shared infrastructure for sync operations.
 *
 * Provides:
 * - Retry logic with exponential backoff driven by [AppResult] outcomes
 * - Network error classification
 * - Progress reporting callbacks
 */
class SyncCoordinator {
    /**
     * Execute a block with retry logic and exponential backoff.
     *
     * The block returns [AppResult]; on [AppResult.Failure] the retry decision is
     * driven by [com.calypsan.listenup.api.error.AppError.isRetryable] — consistent
     * with the project-wide semantics (only errors the middleware can blindly re-fire
     * are marked retryable; user-action paths such as re-auth are not).
     *
     * [CancellationException] always propagates — never retried, never swallowed.
     *
     * @param maxRetries Maximum number of attempts (including the first)
     * @param initialDelay Initial delay between retries
     * @param onRetry Callback invoked before each retry attempt
     * @param block The suspend block to execute; must return [AppResult]
     * @return The last [AppResult] produced — [AppResult.Success] on first win,
     *   [AppResult.Failure] after all attempts are exhausted
     */
    suspend fun <T> withRetry(
        maxRetries: Int = MAX_RETRIES,
        initialDelay: Duration = INITIAL_RETRY_DELAY,
        onRetry: (attempt: Int, maxAttempts: Int) -> Unit = { _, _ -> },
        block: suspend () -> AppResult<T>,
    ): AppResult<T> {
        var lastFailure: AppResult.Failure? = null
        var retryDelay = initialDelay

        repeat(maxRetries) { attempt ->
            if (attempt > 0) {
                logger.info {
                    "Retry attempt ${attempt + 1}/$maxRetries after ${retryDelay.inWholeMilliseconds}ms"
                }
                onRetry(attempt + 1, maxRetries)
                delay(retryDelay)
                retryDelay = (retryDelay * RETRY_BACKOFF_MULTIPLIER).coerceAtMost(MAX_RETRY_DELAY)
            }

            // Re-throw coroutine cancellation — never retry, never swallow.
            try {
                when (val result = block()) {
                    is AppResult.Success -> return result
                    is AppResult.Failure -> {
                        lastFailure = result
                        if (!result.error.isRetryable || attempt == maxRetries - 1) {
                            logger.warn { "Non-retryable or final attempt failure: ${result.error.code}" }
                            return result
                        }
                        logger.warn { "Attempt ${attempt + 1} failed (retryable): ${result.error.code}" }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            }
        }

        return lastFailure
            ?: AppResult.Failure(
                TransportError.Server5xx(statusCode = 0, debugInfo = "Retry exhausted with unknown failure"),
            )
    }

    /**
     * Check if an exception indicates the server is unreachable.
     *
     * This detects connection errors that suggest the server is not running
     * or the URL is incorrect, such as:
     * - Connection refused (ECONNREFUSED)
     * - Connection timeout
     * - Host unreachable
     *
     * @param e The exception to check
     * @return true if the error indicates server is unreachable
     */
    fun isServerUnreachableError(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            when {
                current is ConnectTimeoutException -> {
                    return true
                }

                current is IOException -> {
                    val message = current.message?.lowercase() ?: ""
                    @Suppress("ComplexCondition")
                    if (message.contains("econnrefused") ||
                        message.contains("connection refused") ||
                        message.contains("failed to connect") ||
                        message.contains("no route to host") ||
                        message.contains("host unreachable") ||
                        message.contains("network is unreachable")
                    ) {
                        return true
                    }
                }

                current::class.simpleName == "ConnectException" -> {
                    val message = current.message?.lowercase() ?: ""
                    if (message.contains("econnrefused") ||
                        message.contains("connection refused") ||
                        message.contains("failed to connect")
                    ) {
                        return true
                    }
                }
            }
            current = current.cause
        }
        return false
    }

    companion object {
        const val MAX_RETRIES = 3
        val INITIAL_RETRY_DELAY = 1.seconds
        val MAX_RETRY_DELAY = 30.seconds
        const val RETRY_BACKOFF_MULTIPLIER = 2.0
    }
}
