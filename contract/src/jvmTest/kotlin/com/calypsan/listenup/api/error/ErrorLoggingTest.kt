package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Pins `diagnosticLogLine` — the seam the global error snackbar logs (finding #5b) so a user's report
 * carries the correlation id that ties back to the operator's server log line.
 */
class ErrorLoggingTest :
    FunSpec({
        test("includes the correlationId when the error carries one") {
            val error = AuthError.SessionExpired(correlationId = "cid-abc-123")

            val line = error.diagnosticLogLine()

            line shouldContain "cid=cid-abc-123"
            line shouldContain "AUTH_SESSION_EXPIRED"
            line shouldContain error.message
        }

        test("omits the cid clause for a purely client-local error with no correlationId") {
            val error = TransportError.NetworkUnavailable()
            error.correlationId shouldBe null

            val line = error.diagnosticLogLine()

            line shouldContain "TRANSPORT_NETWORK_UNAVAILABLE"
            line.contains("cid=") shouldBe false
        }

        // The line logged where an error reaches the user. `message` is a constant, so on its own it
        // says WHICH failure and never WHY — the per-instance debugInfo is the why.
        test("the surfaced line carries the diagnostic line and the debugInfo") {
            val error = TransportError.NetworkUnavailable(debugInfo = "Connection refused: rupert:8080")

            val line = error.surfacedLogLine()

            line shouldContain error.diagnosticLogLine()
            line shouldContain "Connection refused: rupert:8080"
        }

        test("the surfaced line is the diagnostic line alone when there is no debugInfo") {
            val error = AuthError.SessionExpired(correlationId = "cid-1")

            error.surfacedLogLine() shouldBe error.diagnosticLogLine()
        }
    })
