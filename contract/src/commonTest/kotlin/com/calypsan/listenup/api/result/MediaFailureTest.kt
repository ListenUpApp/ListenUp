package com.calypsan.listenup.api.result

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.TranscodeError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.builtins.serializer

/**
 * A player fetching audio from the server sees a status and, maybe, a body. #1742 was a client that
 * read every 5xx as a dropped connection and retried a segment the server had already said it could
 * never produce — every eight seconds, forever. What the server meant has to decide what happens.
 */
class MediaFailureTest :
    FunSpec({
        fun reply(error: AppError): String = contractJson.encodeToString(AppResult.serializer(Unit.serializer()), AppResult.Failure(error))

        test("a server that says the segment can never be produced ends playback, keeping its reference") {
            val failure =
                MediaFailure.of(
                    status = 500,
                    body = reply(TranscodeError.TranscodeFailed(correlationId = "c-9", debugInfo = "segment 9109")),
                )

            val terminal = failure.shouldBeInstanceOf<MediaFailure.Terminal>()
            terminal.error.shouldBeInstanceOf<PlaybackError.CouldNotStart>()
            terminal.error.correlationId shouldBe "c-9"
            terminal.error.debugInfo.orEmpty() shouldContain "TRANSCODE_FAILED"
        }

        test("a server that says to try again later is retried") {
            MediaFailure.of(status = 503, body = reply(TranscodeError.TranscoderBusy())) shouldBe MediaFailure.Transient
        }

        test("without a typed body, a gateway or overload status is retried") {
            listOf(502, 503, 504).forEach { status -> MediaFailure.of(status, body = null) shouldBe MediaFailure.Transient }
            MediaFailure.of(status = 503, body = "<html>Bad gateway</html>") shouldBe MediaFailure.Transient
        }

        // A bare 500 is the server's own fault, and the same request will meet the same fault.
        test("without a typed body, an internal server error is not retried") {
            MediaFailure.of(status = 500, body = null).shouldBeInstanceOf<MediaFailure.Terminal>()
            MediaFailure.of(status = 500, body = "").shouldBeInstanceOf<MediaFailure.Terminal>()
        }
    })
