package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.error.TranscodeError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.http.HttpStatusCode

/**
 * A 5xx is the server's own fault, so its log line must say why. #1742 shipped a book whose last
 * segment failed on every request with nothing in the log but the error's code: the reason was in
 * `debugInfo`, which already went to the client and never to the operator.
 */
class RouteResponsesLogTest :
    FunSpec({
        test("a server error's log line carries its debugInfo beside the correlation id") {
            val line =
                appErrorLogLine(
                    error = TranscodeError.TranscodeFailed(correlationId = "c-1", debugInfo = "segment 9 holds 23 frames"),
                    status = HttpStatusCode.InternalServerError,
                    path = "/api/v1/hls/b/f/seg/9.aac",
                )

            line shouldContain "code=TRANSCODE_FAILED"
            line shouldContain "correlationId=c-1"
            line shouldContain "debugInfo=segment 9 holds 23 frames"
        }

        // A 4xx is the caller's doing and logs at debug; its detail stays out of the line.
        test("a client error's log line leaves debugInfo out") {
            val line =
                appErrorLogLine(
                    error = TranscodeError.TranscoderBusy(debugInfo = "two sessions running"),
                    status = HttpStatusCode.TooManyRequests,
                    path = "/api/v1/hls/b/f/seg/0.aac",
                )

            line shouldNotContain "two sessions running"
        }
    })
