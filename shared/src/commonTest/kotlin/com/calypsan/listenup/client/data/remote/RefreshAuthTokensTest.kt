package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.domain.repository.AuthSession
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Tests for [refreshAuthTokens] token refresh error handling.
 *
 * Verifies that auth tokens are only cleared on definitive auth rejections
 * (401/403), NOT on transient errors like network failures or server errors.
 */
class RefreshAuthTokensTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val authSession: AuthSession = mock()
        val authApi: AuthApiContract = mock()
        val refreshToken = RefreshToken("test-refresh-token")
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()
        everySuspend { fixture.authSession.getRefreshToken() } returns fixture.refreshToken
        return fixture
    }

    /**
     * Creates a [ResponseException] with a mock response carrying the given HTTP status code.
     * Uses [ResponseException] (the parent of [io.ktor.client.plugins.ClientRequestException]
     * and [io.ktor.client.plugins.ServerResponseException]) because Ktor's concrete exception
     * constructors access `response.call.request.url` which requires mocking final classes.
     */
    private fun httpException(statusCode: Int): ResponseException {
        val mockResponse: HttpResponse = mock()
        every { mockResponse.status } returns HttpStatusCode.fromValue(statusCode)
        return ResponseException(mockResponse, "HTTP $statusCode")
    }

    // ========== Tokens NOT Cleared Tests ==========

    @Test
    fun `does not clear tokens on network error`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authApi.refresh(any()) } throws IOException("Connection refused")
            // clearAuthTokens throws AssertionError if called - Error is not caught by catch(Exception)
            everySuspend { fixture.authSession.clearAuthTokens() } throws
                AssertionError("clearAuthTokens() must not be called for network errors")

            // When
            val result = refreshAuthTokens(fixture.authSession, fixture.authApi)

            // Then
            assertNull(result)
        }

    @Test
    fun `does not clear tokens on 503 Service Unavailable`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authApi.refresh(any()) } throws httpException(503)
            everySuspend { fixture.authSession.clearAuthTokens() } throws
                AssertionError("clearAuthTokens() must not be called for 503 errors")

            // When
            val result = refreshAuthTokens(fixture.authSession, fixture.authApi)

            // Then
            assertNull(result)
        }

    // ========== Tokens ARE Cleared Tests ==========

    @Test
    fun `clears tokens on 401 Unauthorized`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authApi.refresh(any()) } throws httpException(401)
            everySuspend { fixture.authSession.clearAuthTokens() } returns Unit

            // When
            val result = refreshAuthTokens(fixture.authSession, fixture.authApi)

            // Then
            assertNull(result)
            verifySuspend { fixture.authSession.clearAuthTokens() }
        }

    @Test
    fun `clears tokens on 403 Forbidden`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.authApi.refresh(any()) } throws httpException(403)
            everySuspend { fixture.authSession.clearAuthTokens() } returns Unit

            // When
            val result = refreshAuthTokens(fixture.authSession, fixture.authApi)

            // Then
            assertNull(result)
            verifySuspend { fixture.authSession.clearAuthTokens() }
        }
}
