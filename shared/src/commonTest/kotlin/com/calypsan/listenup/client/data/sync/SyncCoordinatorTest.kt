package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.core.AppResult
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for SyncCoordinator.
 *
 * The retry contract is now AppResult-native: blocks return [AppResult] and
 * [SyncCoordinator.withRetry] decides whether to retry based on
 * [com.calypsan.listenup.api.error.AppError.isRetryable]. Cancellation still
 * propagates as a [CancellationException].
 *
 * Tests cover:
 * - Retry logic with exponential backoff (success, retryable failure, non-retryable failure)
 * - CancellationException handling
 * - Error classification for server unreachable errors (connection-level Throwables)
 * - Callback invocation during retries
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    private fun retryableError(detail: String = "transient"): TransportError = TransportError.Server5xx(statusCode = 503, debugInfo = detail)

    private fun nonRetryableError(detail: String = "client error"): TransportError = TransportError.Server4xx(statusCode = 400, debugInfo = detail)

    // ========== withRetry Success Cases ==========

    @Test
    fun `withRetry returns immediately on success`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var callCount = 0

            // When
            val result =
                coordinator.withRetry(
                    maxRetries = 3,
                    initialDelay = 10.milliseconds,
                ) {
                    callCount++
                    AppResult.Success("success")
                }

            // Then
            assertIs<AppResult.Success<String>>(result)
            assertEquals("success", result.data)
            assertEquals(1, callCount)
        }

    @Test
    fun `withRetry succeeds on second attempt after retryable failure`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var callCount = 0

            // When
            val result =
                coordinator.withRetry(
                    maxRetries = 3,
                    initialDelay = 10.milliseconds,
                ) {
                    callCount++
                    if (callCount == 1) AppResult.Failure(retryableError("first")) else AppResult.Success("success on retry")
                }

            // Then
            assertIs<AppResult.Success<String>>(result)
            assertEquals("success on retry", result.data)
            assertEquals(2, callCount)
        }

    @Test
    fun `withRetry succeeds on last attempt`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var callCount = 0

            // When
            val result =
                coordinator.withRetry(
                    maxRetries = 3,
                    initialDelay = 10.milliseconds,
                ) {
                    callCount++
                    if (callCount < 3) AppResult.Failure(retryableError("attempt $callCount")) else AppResult.Success("success on final attempt")
                }

            // Then
            assertIs<AppResult.Success<String>>(result)
            assertEquals("success on final attempt", result.data)
            assertEquals(3, callCount)
        }

    // ========== withRetry Failure Cases ==========

    @Test
    fun `withRetry returns last Failure after all retries exhausted on retryable error`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var callCount = 0

            // When
            val result =
                coordinator.withRetry<String>(
                    maxRetries = 3,
                    initialDelay = 10.milliseconds,
                ) {
                    callCount++
                    AppResult.Failure(retryableError("attempt $callCount"))
                }

            // Then
            assertEquals(3, callCount)
            val failure = assertIs<AppResult.Failure>(result)
            val typed = assertIs<TransportError.Server5xx>(failure.error)
            assertTrue(typed.debugInfo!!.contains("attempt 3"))
        }

    @Test
    fun `withRetry short-circuits on non-retryable error after first attempt`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var callCount = 0

            // When
            val result =
                coordinator.withRetry<String>(
                    maxRetries = 3,
                    initialDelay = 10.milliseconds,
                ) {
                    callCount++
                    AppResult.Failure(nonRetryableError())
                }

            // Then
            assertEquals(1, callCount)
            val failure = assertIs<AppResult.Failure>(result)
            assertIs<TransportError.Server4xx>(failure.error)
        }

    @Test
    fun `withRetry does not retry on CancellationException`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var callCount = 0

            // When/Then
            assertFailsWith<CancellationException> {
                coordinator.withRetry<String>(
                    maxRetries = 3,
                    initialDelay = 10.milliseconds,
                ) {
                    callCount++
                    throw CancellationException("Cancelled")
                }
            }

            // Only called once - no retries
            assertEquals(1, callCount)
        }

    // ========== withRetry Callback Tests ==========

    @Test
    fun `withRetry invokes onRetry callback before each retry`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            val retryAttempts = mutableListOf<Pair<Int, Int>>()

            // When
            coordinator.withRetry<String>(
                maxRetries = 3,
                initialDelay = 10.milliseconds,
                onRetry = { attempt, max -> retryAttempts.add(attempt to max) },
            ) {
                AppResult.Failure(retryableError("always fails"))
            }

            // Then - onRetry called before attempts 2 and 3 (not before first attempt)
            assertEquals(2, retryAttempts.size)
            assertEquals(2 to 3, retryAttempts[0]) // Before 2nd attempt
            assertEquals(3 to 3, retryAttempts[1]) // Before 3rd attempt
        }

    @Test
    fun `withRetry does not invoke onRetry when succeeds first time`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var onRetryCalled = false

            // When
            coordinator.withRetry(
                maxRetries = 3,
                initialDelay = 10.milliseconds,
                onRetry = { _, _ -> onRetryCalled = true },
            ) {
                AppResult.Success("success")
            }

            // Then
            assertFalse(onRetryCalled)
        }

    // ========== withRetry Custom Parameters ==========

    @Test
    fun `withRetry respects custom maxRetries`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var callCount = 0

            // When
            val result =
                coordinator.withRetry<String>(
                    maxRetries = 5,
                    initialDelay = 10.milliseconds,
                ) {
                    callCount++
                    AppResult.Failure(retryableError("attempt $callCount"))
                }

            // Then
            assertEquals(5, callCount)
            assertIs<AppResult.Failure>(result)
        }

    @Test
    fun `withRetry with maxRetries of 1 does not retry`() =
        runTest {
            // Given
            val coordinator = SyncCoordinator()
            var callCount = 0

            // When
            val result =
                coordinator.withRetry<String>(
                    maxRetries = 1,
                    initialDelay = 10.milliseconds,
                ) {
                    callCount++
                    AppResult.Failure(retryableError())
                }

            // Then
            assertEquals(1, callCount)
            assertIs<AppResult.Failure>(result)
        }

    // ========== isServerUnreachableError Tests ==========

    @Test
    fun `isServerUnreachableError returns true for ConnectTimeoutException`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = ConnectTimeoutException("Connection timed out")

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError returns true for IOException with ECONNREFUSED`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = IOException("ECONNREFUSED")

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError returns true for IOException with connection refused`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = IOException("Connection refused")

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError returns true for IOException with failed to connect`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = IOException("Failed to connect to server")

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError returns true for IOException with no route to host`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = IOException("No route to host")

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError returns true for IOException with host unreachable`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = IOException("Host unreachable")

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError returns true for IOException with network is unreachable`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = IOException("Network is unreachable")

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError returns false for generic IOException`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = IOException("Some other IO error")

        // When/Then
        assertFalse(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError returns false for generic RuntimeException`() {
        // Given
        val coordinator = SyncCoordinator()
        val exception = RuntimeException("Some runtime error")

        // When/Then
        assertFalse(coordinator.isServerUnreachableError(exception))
    }

    @Test
    fun `isServerUnreachableError checks nested cause chain`() {
        // Given
        val coordinator = SyncCoordinator()
        val rootCause = IOException("Connection refused")
        val wrapper = RuntimeException("Wrapper", rootCause)

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(wrapper))
    }

    @Test
    fun `isServerUnreachableError handles deeply nested exceptions`() {
        // Given
        val coordinator = SyncCoordinator()
        val rootCause = IOException("ECONNREFUSED")
        val level1 = RuntimeException("Level 1", rootCause)
        val level2 = IllegalStateException("Level 2", level1)
        val level3 = Exception("Level 3", level2)

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(level3))
    }

    @Test
    fun `isServerUnreachableError is case insensitive`() {
        // Given
        val coordinator = SyncCoordinator()

        // When/Then
        assertTrue(coordinator.isServerUnreachableError(IOException("CONNECTION REFUSED")))
        assertTrue(coordinator.isServerUnreachableError(IOException("EconnRefused")))
        assertTrue(coordinator.isServerUnreachableError(IOException("FAILED TO CONNECT")))
    }
}
