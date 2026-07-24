package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TransportErrorTest :
    FunSpec({
        test("NetworkUnavailable is auto-retryable") {
            val err = TransportError.NetworkUnavailable()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "TRANSPORT_NETWORK_UNAVAILABLE"
            err.isRetryable shouldBe true
        }

        test("Timeout is auto-retryable") {
            val err = TransportError.Timeout()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "TRANSPORT_TIMEOUT"
            err.isRetryable shouldBe true
        }

        test("OutcomeUnknown is NOT auto-retryable — the mutation may have already committed") {
            val err = TransportError.OutcomeUnknown()
            err.message shouldBe "The request may not have completed. Check before retrying."
            err.code shouldBe "TRANSPORT_OUTCOME_UNKNOWN"
            err.isRetryable shouldBe false
        }

        test("Server5xx carries the status code and is auto-retryable") {
            val err = TransportError.Server5xx(statusCode = 503)
            err.statusCode shouldBe 503
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "TRANSPORT_SERVER_5XX"
            err.isRetryable shouldBe true
        }

        test("Server4xx is not auto-retryable by default") {
            val err = TransportError.Server4xx(statusCode = 404)
            err.statusCode shouldBe 404
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "TRANSPORT_SERVER_4XX"
            err.isRetryable shouldBe false
        }

        test("DataMalformed is not auto-retryable") {
            val err = TransportError.DataMalformed(detail = "JSON parse failed at offset 42")
            err.detail shouldBe "JSON parse failed at offset 42"
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "TRANSPORT_DATA_MALFORMED"
            err.isRetryable shouldBe false
        }
    })
