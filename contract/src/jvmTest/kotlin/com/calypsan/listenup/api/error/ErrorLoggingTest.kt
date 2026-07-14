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
            (line.contains("cid=")) shouldBe false
        }
    })
