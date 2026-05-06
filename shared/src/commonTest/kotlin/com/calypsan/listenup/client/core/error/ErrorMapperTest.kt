package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.checkIs
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for ErrorMapper.
 *
 * Covers the unified-AppError contract introduced in Task 13: the mapper produces
 * `api.error.AppError` subtypes (`TransportError.*`, `ValidationError`, `InternalError`)
 * with body-level `message`/`code`/`isRetryable` constants per subtype and per-instance
 * detail in `debugInfo`.
 *
 * Note: Ktor network exceptions (ConnectTimeoutException, SocketTimeoutException,
 * ResponseException, HttpRequestTimeoutException) have complex constructors that
 * make them difficult to instantiate in unit tests. Those mappings are verified
 * through HttpClientErrorHandlingTest.
 */
class ErrorMapperTest {
    // ========== SerializationException → TransportError.DataMalformed ==========

    @Test
    fun `map SerializationException returns DataMalformed`() {
        val exception = SerializationException("Failed to parse JSON")
        val error = ErrorMapper.map(exception)

        val dataMalformed = assertIs<TransportError.DataMalformed>(error)
        assertEquals("Server response was malformed.", dataMalformed.message)
        assertEquals("TRANSPORT_DATA_MALFORMED", dataMalformed.code)
        assertEquals("Failed to parse JSON", dataMalformed.detail)
    }

    @Test
    fun `DataMalformed is not retryable`() {
        val exception = SerializationException("Parse error")
        val error = ErrorMapper.map(exception)

        val dataMalformed = assertIs<TransportError.DataMalformed>(error)
        assertEquals(false, dataMalformed.isRetryable)
    }

    @Test
    fun `map SerializationException includes debug info`() {
        val exception = SerializationException("Unexpected JSON token")
        val error = ErrorMapper.map(exception)

        val dataMalformed = assertIs<TransportError.DataMalformed>(error)
        assertEquals("Unexpected JSON token", dataMalformed.debugInfo)
    }

    @Test
    fun `map SerializationException with null message uses fallback detail`() {
        val exception = SerializationException(null as String?)
        val error = ErrorMapper.map(exception)

        val dataMalformed = assertIs<TransportError.DataMalformed>(error)
        assertEquals("deserialization failed", dataMalformed.detail)
    }

    // ========== IOException → TransportError.NetworkUnavailable ==========

    @Test
    fun `map IOException returns NetworkUnavailable`() {
        val exception = IOException("Connection refused")
        val error = ErrorMapper.map(exception)

        val networkUnavailable = assertIs<TransportError.NetworkUnavailable>(error)
        assertEquals("No internet connection. Check your network.", networkUnavailable.message)
        assertEquals("TRANSPORT_NETWORK_UNAVAILABLE", networkUnavailable.code)
        assertEquals("Connection refused", networkUnavailable.debugInfo)
    }

    @Test
    fun `NetworkUnavailable is retryable`() {
        val exception = IOException("Network down")
        val error = ErrorMapper.map(exception)

        val networkUnavailable = assertIs<TransportError.NetworkUnavailable>(error)
        assertEquals(true, networkUnavailable.isRetryable)
    }

    // ========== IllegalArgumentException → ValidationError ==========

    @Test
    fun `map IllegalArgumentException returns ValidationError preserving message`() {
        val exception = IllegalArgumentException("Bad argument")
        val error = ErrorMapper.map(exception)

        val validationError = assertIs<ValidationError>(error)
        assertEquals("Bad argument", validationError.message)
        assertEquals("VALIDATION_ERROR", validationError.code)
        assertEquals("Bad argument", validationError.debugInfo)
        assertEquals(false, validationError.isRetryable)
    }

    @Test
    fun `map IllegalArgumentException with null message uses fallback`() {
        val exception = IllegalArgumentException()
        val error = ErrorMapper.map(exception)

        val validationError = assertIs<ValidationError>(error)
        assertEquals("Invalid input.", validationError.message)
    }

    // ========== Unknown / catch-all → InternalError ==========

    @Test
    fun `map unknown exception returns InternalError`() {
        val exception = IllegalStateException("Something went wrong")
        val error = ErrorMapper.map(exception)

        val internalError = assertIs<InternalError>(error)
        assertEquals("Something went wrong on the server.", internalError.message)
        assertEquals("INTERNAL_ERROR", internalError.code)
    }

    @Test
    fun `InternalError is not retryable`() {
        val exception = RuntimeException("Random error")
        val error = ErrorMapper.map(exception)

        val internalError = assertIs<InternalError>(error)
        assertEquals(false, internalError.isRetryable)
    }

    @Test
    fun `map unknown exception preserves throwable text in debug info`() {
        val exception = RuntimeException("Custom error message")
        val error = ErrorMapper.map(exception)

        val internalError = assertIs<InternalError>(error)
        assertTrue(internalError.debugInfo?.contains("Custom error message") == true)
        assertTrue(internalError.debugInfo?.contains("RuntimeException") == true)
    }

    @Test
    fun `map custom exception returns InternalError`() {
        class CustomException(message: String) : Exception(message)
        val exception = CustomException("Custom domain error")
        val error = ErrorMapper.map(exception)

        checkIs<InternalError>(error)
    }

    @Test
    fun `map NullPointerException returns InternalError`() {
        val exception = NullPointerException("null reference")
        val error = ErrorMapper.map(exception)

        checkIs<InternalError>(error)
    }

    @Test
    fun `map IndexOutOfBoundsException returns InternalError`() {
        val exception = IndexOutOfBoundsException("index 5 out of bounds")
        val error = ErrorMapper.map(exception)

        checkIs<InternalError>(error)
    }
}
