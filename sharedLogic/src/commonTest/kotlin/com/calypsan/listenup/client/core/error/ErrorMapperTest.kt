package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.data.remote.ServerUrlNotConfiguredException
import com.calypsan.listenup.client.data.remote.model.EnvelopeMismatchException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/**
 * Tests for ErrorMapper.
 *
 * Covers the unified-AppError contract: the mapper produces
 * `api.error.AppError` subtypes (`TransportError.*`, `ValidationError`, `InternalError`)
 * with body-level `message`/`code`/`isRetryable` constants per subtype and per-instance
 * detail in `debugInfo`.
 *
 * Note: Ktor network exceptions (ConnectTimeoutException, SocketTimeoutException,
 * ResponseException, HttpRequestTimeoutException) have complex constructors that
 * make them difficult to instantiate in unit tests. Those mappings are verified
 * through HttpClientErrorHandlingTest.
 */
class ErrorMapperTest :
    FunSpec({
        // ========== EnvelopeMismatchException → TransportError.ContractMismatch ==========

        test("map EnvelopeMismatchException returns ContractMismatch, not InternalError or AuthError") {
            val exception = EnvelopeMismatchException("Envelope version mismatch. Expected v=1, got v=2.")
            val error = ErrorMapper.map(exception)

            val contractMismatch = error.shouldBeInstanceOf<TransportError.ContractMismatch>()
            contractMismatch.message shouldBe "The app and server versions don't match."
            contractMismatch.code shouldBe "TRANSPORT_CONTRACT_MISMATCH"
            contractMismatch.isRetryable shouldBe false
            contractMismatch.detail shouldBe "Envelope version mismatch. Expected v=1, got v=2."
            contractMismatch.debugInfo shouldBe "Envelope version mismatch. Expected v=1, got v=2."
        }

        test("ContractMismatch is not retryable") {
            val exception = EnvelopeMismatchException("Response missing 'v' field.")
            val error = ErrorMapper.map(exception)

            val contractMismatch = error.shouldBeInstanceOf<TransportError.ContractMismatch>()
            contractMismatch.isRetryable shouldBe false
        }

        // ========== SerializationException → TransportError.DataMalformed ==========

        test("map SerializationException returns DataMalformed") {
            val exception = SerializationException("Failed to parse JSON")
            val error = ErrorMapper.map(exception)

            val dataMalformed = error.shouldBeInstanceOf<TransportError.DataMalformed>()
            dataMalformed.message shouldBe "Server response was malformed."
            dataMalformed.code shouldBe "TRANSPORT_DATA_MALFORMED"
            dataMalformed.detail shouldBe "Failed to parse JSON"
        }

        test("DataMalformed is not retryable") {
            val exception = SerializationException("Parse error")
            val error = ErrorMapper.map(exception)

            val dataMalformed = error.shouldBeInstanceOf<TransportError.DataMalformed>()
            dataMalformed.isRetryable shouldBe false
        }

        test("map SerializationException includes debug info") {
            val exception = SerializationException("Unexpected JSON token")
            val error = ErrorMapper.map(exception)

            val dataMalformed = error.shouldBeInstanceOf<TransportError.DataMalformed>()
            dataMalformed.debugInfo shouldBe "Unexpected JSON token"
        }

        test("map SerializationException with null message uses fallback detail") {
            val exception = SerializationException(null as String?)
            val error = ErrorMapper.map(exception)

            val dataMalformed = error.shouldBeInstanceOf<TransportError.DataMalformed>()
            dataMalformed.detail shouldBe "deserialization failed"
        }

        // ========== IOException → TransportError.NetworkUnavailable ==========

        test("map IOException returns NetworkUnavailable") {
            val exception = IOException("Connection refused")
            val error = ErrorMapper.map(exception)

            val networkUnavailable = error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            networkUnavailable.message shouldBe "No internet connection. Check your network."
            networkUnavailable.code shouldBe "TRANSPORT_NETWORK_UNAVAILABLE"
            networkUnavailable.debugInfo shouldBe "Connection refused"
        }

        test("NetworkUnavailable is retryable") {
            val exception = IOException("Network down")
            val error = ErrorMapper.map(exception)

            val networkUnavailable = error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            networkUnavailable.isRetryable shouldBe true
        }

        // ========== IllegalArgumentException → ValidationError ==========

        test("map IllegalArgumentException returns ValidationError preserving message") {
            val exception = IllegalArgumentException("Bad argument")
            val error = ErrorMapper.map(exception)

            val validationError = error.shouldBeInstanceOf<ValidationError>()
            validationError.message shouldBe "Bad argument"
            validationError.code shouldBe "VALIDATION_ERROR"
            validationError.debugInfo shouldBe "Bad argument"
            validationError.isRetryable shouldBe false
        }

        test("map IllegalArgumentException with null message uses fallback") {
            val exception = IllegalArgumentException()
            val error = ErrorMapper.map(exception)

            val validationError = error.shouldBeInstanceOf<ValidationError>()
            validationError.message shouldBe "Invalid input."
        }

        // ========== Unknown / catch-all → InternalError ==========

        test("map unknown exception returns InternalError") {
            val exception = IllegalStateException("Something went wrong")
            val error = ErrorMapper.map(exception)

            val internalError = error.shouldBeInstanceOf<InternalError>()
            internalError.message shouldBe "Something went wrong on the server."
            internalError.code shouldBe "INTERNAL_ERROR"
        }

        test("InternalError is not retryable") {
            val exception = RuntimeException("Random error")
            val error = ErrorMapper.map(exception)

            val internalError = error.shouldBeInstanceOf<InternalError>()
            internalError.isRetryable shouldBe false
        }

        test("map unknown exception preserves throwable text in debug info") {
            val exception = RuntimeException("Custom error message")
            val error = ErrorMapper.map(exception)

            val internalError = error.shouldBeInstanceOf<InternalError>()
            (internalError.debugInfo?.contains("Custom error message") == true) shouldBe true
            (internalError.debugInfo?.contains("RuntimeException") == true) shouldBe true
        }

        test("map unknown exception with null message produces ClassName-null debug info") {
            val error = ErrorMapper.map(RuntimeException())

            val internalError = error.shouldBeInstanceOf<InternalError>()
            internalError.debugInfo shouldBe "RuntimeException: null"
        }

        test("map custom exception returns InternalError") {
            class CustomException(
                message: String,
            ) : Exception(message)
            val exception = CustomException("Custom domain error")
            val error = ErrorMapper.map(exception)

            checkIs<InternalError>(error)
        }

        // ========== ServerUrlNotConfiguredException → TransportError.NetworkUnavailable ==========

        test("map ServerUrlNotConfiguredException returns NetworkUnavailable, not InternalError") {
            // "No server configured yet" is an expected pre-connection state, not a bug — it must
            // map to a typed transport error (matching InstanceRepositoryImpl's precedent) so the
            // boundary can fold it quietly instead of surfacing a generic "server error".
            val error = ErrorMapper.map(ServerUrlNotConfiguredException())

            val networkUnavailable = error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            (networkUnavailable.debugInfo?.contains("Server URL not configured") == true) shouldBe true
        }

        test("map NullPointerException returns InternalError") {
            val exception = NullPointerException("null reference")
            val error = ErrorMapper.map(exception)

            checkIs<InternalError>(error)
        }

        test("map IndexOutOfBoundsException returns InternalError") {
            val exception = IndexOutOfBoundsException("index 5 out of bounds")
            val error = ErrorMapper.map(exception)

            checkIs<InternalError>(error)
        }
    })
