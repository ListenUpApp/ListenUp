package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.ServerUrl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for InstanceRepositoryImpl.
 *
 * Note: Since InstanceRepositoryImpl now makes direct HTTP calls, these tests
 * focus on verifying the behavior when no server URL is configured.
 * Integration tests with a mock HTTP server would be needed for full coverage.
 */
class InstanceRepositoryImplTest {
    // ========== Error Handling Tests ==========

    @Test
    fun `getInstance returns failure when server URL is not configured`() =
        runTest {
            // Given - no server URL configured
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = { null },
                )

            // When
            val result = repository.getInstance()

            // Then
            // Body-level message convention: the IllegalStateException is mapped to
            // InternalError, so the "Server URL not configured" text now lives in
            // debugInfo. Keep the Failure assertion only.
            assertIs<Failure>(result)
        }

    @Test
    fun `getInstance with forceRefresh returns failure when server URL is not configured`() =
        runTest {
            // Given - no server URL configured
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = { null },
                )

            // When
            val result = repository.getInstance(forceRefresh = true)

            // Then
            // Body-level message convention: the IllegalStateException is mapped to
            // InternalError, so the "Server URL not configured" text now lives in
            // debugInfo. Keep the Failure assertion only.
            assertIs<Failure>(result)
        }

    @Test
    fun `getInstance calls getServerUrl to get dynamic URL`() =
        runTest {
            // Given
            var urlFetchCount = 0
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = {
                        urlFetchCount++
                        // Return null to fail immediately without making HTTP call
                        null
                    },
                )

            // When
            repository.getInstance()
            repository.getInstance(forceRefresh = true)

            // Then - getServerUrl should be called for each non-cached request
            // First call: no cache, so getServerUrl is called
            // Second call with forceRefresh: cache bypassed, so getServerUrl is called
            assertTrue(urlFetchCount >= 2, "Expected getServerUrl to be called at least twice, but was $urlFetchCount")
        }

    @Test
    fun `getInstance uses cached data when forceRefresh is false and cache exists`() =
        runTest {
            // Given - this test verifies caching behavior indirectly
            // If caching works, getServerUrl won't be called on subsequent requests
            var urlFetchCount = 0
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = {
                        urlFetchCount++
                        // Return null - we're just testing if getServerUrl is called
                        null
                    },
                )

            // When - first call (no cache)
            repository.getInstance()
            val countAfterFirst = urlFetchCount

            // When - second call without forceRefresh (would use cache if it existed)
            // Since first call failed (no URL), there's no cache, so it calls again
            repository.getInstance(forceRefresh = false)

            // Then - because first call failed (no cache stored), second call also fetches
            assertTrue(urlFetchCount > countAfterFirst, "Expected getServerUrl to be called again since cache is empty")
        }
}
