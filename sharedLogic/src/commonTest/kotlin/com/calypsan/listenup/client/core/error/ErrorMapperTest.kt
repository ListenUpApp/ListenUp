package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.data.remote.ServerUrlNotConfiguredException
import com.calypsan.listenup.client.data.remote.model.EnvelopeMismatchException
import io.ktor.client.plugins.websocket.WebSocketException
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

        // ========== ClientValidationException → ValidationError ==========

        test("map ClientValidationException returns ValidationError carrying userMessage + field") {
            val exception = ClientValidationException("Please enter a valid email address", field = "email")
            val error = ErrorMapper.map(exception)

            val validationError = error.shouldBeInstanceOf<ValidationError>()
            validationError.message shouldBe "Please enter a valid email address"
            validationError.field shouldBe "email"
            validationError.code shouldBe "VALIDATION_ERROR"
            validationError.isRetryable shouldBe false
        }

        test("map bare IllegalArgumentException does NOT leak its raw message as a user ValidationError") {
            // A library `require(...)` throws IllegalArgumentException("Failed requirement.") — an
            // internal fault. It must map to the sanitized InternalError, never a user-facing
            // ValidationError echoing the raw require text as if it were the user's input problem.
            val exception = IllegalArgumentException("Failed requirement.")
            val error = ErrorMapper.map(exception)

            error.shouldBeInstanceOf<InternalError>()
            error.message shouldBe "Something went wrong on the server."
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

        // ========== Dead RPC client → TransportError.NetworkUnavailable ==========

        test("map dead-client IllegalStateException returns NetworkUnavailable, not InternalError") {
            // A cancelled/torn-down RpcClient surfaces as an IllegalStateException whose message
            // contains "RpcClient was cancelled". By the time it reaches ErrorMapper the engine has
            // already split out the post-delivery "outcome unknown" case, so this is a pre-delivery
            // transport failure — type it as a retryable NetworkUnavailable, not a scary InternalError.
            val exception = IllegalStateException("RpcClient was cancelled: connection closed")
            val error = ErrorMapper.map(exception)

            val networkUnavailable = error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            networkUnavailable.isRetryable shouldBe true
            (networkUnavailable.debugInfo?.contains("RpcClient was cancelled") == true) shouldBe true
        }

        // ========== Non-401 WebSocketException → TransportError.NetworkUnavailable ==========

        test("map non-401 WebSocketException returns NetworkUnavailable, not InternalError") {
            val exception = WebSocketException("Handshake exception, expected status code 101 but was 500")
            val error = ErrorMapper.map(exception)

            val networkUnavailable = error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            networkUnavailable.isRetryable shouldBe true
        }

        test("map handshake-401 WebSocketException still returns SessionExpired") {
            // Regression guard: the new generic-WebSocketException arm must be ordered AFTER the
            // existing isWsHandshake401 arm, so a 401 handshake keeps mapping to SessionExpired
            // (drives the app back to login) rather than being folded into NetworkUnavailable.
            val exception = WebSocketException("Handshake exception, expected status code 101 but was 401")
            val error = ErrorMapper.map(exception)

            error.shouldBeInstanceOf<AuthError.SessionExpired>()
        }

        // ========== TLS/SSL failure → ServerConnectError.TlsFailure ==========

        test("map SSL handshake exception returns typed TlsFailure, not NetworkUnavailable") {
            // Classified by CLASS NAME (SSLHandshakeException) — not a message substring. A genuine
            // TLS failure means the https/wss scheme is wrong (plaintext server), so verification can
            // retry the http/ws candidate.
            val exception = SslHandshakeException("Unrecognized SSL message, plaintext connection?")
            val error = ErrorMapper.map(exception)

            val tls = error.shouldBeInstanceOf<ServerConnectError.TlsFailure>()
            tls.code shouldBe "SERVER_CONNECT_TLS_FAILURE"
            tls.debugInfo shouldBe "Unrecognized SSL message, plaintext connection?"
        }

        test("map SSL failure nested as a cause is still classified as TlsFailure") {
            val exception = RuntimeException("connect failed", SslHandshakeException("cert rejected"))
            val error = ErrorMapper.map(exception)

            error.shouldBeInstanceOf<ServerConnectError.TlsFailure>()
        }

        test("map non-101 WebSocketException (proxy 500) is NOT misread as TlsFailure") {
            // The message contains the word "Handshake", which the old substring heuristic misread as
            // an SSL error and skipped to the next URL candidate. WebSocketException means TLS SUCCEEDED
            // but the HTTP upgrade returned a non-101 status — it must stay NetworkUnavailable.
            val exception = WebSocketException("Handshake exception, expected status code 101 but was 500")
            val error = ErrorMapper.map(exception)

            error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
        }
    })

/** A stand-in for the platform SSL/TLS exception whose simple class name carries the "SSL" marker. */
private class SslHandshakeException(
    message: String,
) : IOException(message)
