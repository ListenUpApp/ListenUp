package com.calypsan.listenup.web.playback

import org.w3c.dom.MediaError
import io.kotest.matchers.types.shouldBeInstanceOf
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.AudioSegment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The two pure functions the browser player is built on: book-relative → segment coordinates,
 * and which mechanism a segment should be played by. Both are deliberately free of the DOM, so
 * the arithmetic that decides *where* a listener lands can be proved without a media element.
 */
class HtmlAudioPlayerTest :
    FunSpec({
        val firstDurationMs = 10_000L
        val secondDurationMs = 5_000L
        val segments =
            listOf(
                AudioSegment(
                    url = "a",
                    hlsUrl = null,
                    localPath = null,
                    durationMs = firstDurationMs,
                    offsetMs = 0,
                ),
                AudioSegment(
                    url = "b",
                    hlsUrl = null,
                    localPath = null,
                    durationMs = secondDurationMs,
                    offsetMs = firstDurationMs,
                ),
            )

        test("a book position inside the first segment resolves to that segment") {
            val insideFirstMs = 4_000L

            resolveSegment(segments, insideFirstMs) shouldBe
                SegmentSeek(index = 0, offsetInSegmentMs = insideFirstMs)
        }

        test("a book position inside a later segment subtracts the accumulated offset") {
            val insideSecondMs = 12_500L

            resolveSegment(segments, insideSecondMs) shouldBe
                SegmentSeek(index = 1, offsetInSegmentMs = insideSecondMs - firstDurationMs)
        }

        test("a position past the end clamps to the last segment rather than throwing") {
            val pastTheEndMs = 99_000L

            resolveSegment(segments, pastTheEndMs) shouldBe
                SegmentSeek(index = 1, offsetInSegmentMs = secondDurationMs)
        }

        test("a negative position clamps to the start") {
            resolveSegment(segments, -1) shouldBe SegmentSeek(index = 0, offsetInSegmentMs = 0)
        }

        test("HLS is preferred over the direct url when the server sent one") {
            val transcoded =
                AudioSegment(url = "direct", hlsUrl = "playlist.m3u8", localPath = null, durationMs = 1, offsetMs = 0)

            sourceFor(transcoded) shouldBe SegmentSource.Hls("playlist.m3u8")
        }

        test("the direct url is used when no HLS url was sent") {
            val direct = AudioSegment(url = "direct", hlsUrl = null, localPath = null, durationMs = 1, offsetMs = 0)

            sourceFor(direct) shouldBe SegmentSource.Direct("direct")
        }

        test("a play() refused for want of a gesture is AutoplayBlocked, an abort is nothing") {
            playRefusal("NotAllowedError") shouldBe PlaybackError.AutoplayBlocked()
            playRefusal("AbortError") shouldBe null
            playRefusal("SecurityError").shouldBeInstanceOf<PlaybackError.CouldNotStart>()
        }

        test("an hls.js or element failure is typed by its cause, with the browser's words kept as debugInfo") {
            hlsFailure(HlsFatalError("networkError", "fragLoadError")) shouldBe
                PlaybackError.ConnectionLost(debugInfo = "networkError: fragLoadError")
            hlsFailure(HlsFatalError("mediaError", "bufferAppendError")) shouldBe
                PlaybackError.UnsupportedFormat(debugInfo = "mediaError: bufferAppendError")
            hlsFailure(HlsFatalError("otherError", "internalException")) shouldBe
                PlaybackError.CouldNotStart(debugInfo = "otherError: internalException")

            mediaElementFailure(MediaError.MEDIA_ERR_NETWORK, "dropped").shouldBeInstanceOf<PlaybackError.ConnectionLost>()
            mediaElementFailure(MediaError.MEDIA_ERR_DECODE, "decode").shouldBeInstanceOf<PlaybackError.UnsupportedFormat>()
            mediaElementFailure(MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED, "src")
                .shouldBeInstanceOf<PlaybackError.UnsupportedFormat>()
            mediaElementFailure(MediaError.MEDIA_ERR_ABORTED, "aborted").shouldBeInstanceOf<PlaybackError.CouldNotStart>()
            mediaElementFailure(null, "why") shouldBe PlaybackError.CouldNotStart(debugInfo = "why")
        }

        // #1742: a segment the server said it can never produce is not a dropped connection, and
        // its reference is what joins the card to the server's log line.
        test("a segment the server refused for good fails as that refusal, carrying its reference") {
            val body = """{"type":"Failure","error":{"type":"TranscodeError.TranscodeFailed","correlationId":"ref-1"}}"""

            val failure = hlsFailure(HlsFatalError("networkError", "fragLoadError", status = 500, body = body))

            failure.shouldBeInstanceOf<PlaybackError.CouldNotStart>()
            failure.correlationId shouldBe "ref-1"
        }

        test("a segment failed by an overloaded server or a proxy is still a dropped connection") {
            hlsFailure(HlsFatalError("networkError", "fragLoadError", status = 503))
                .shouldBeInstanceOf<PlaybackError.ConnectionLost>()
        }

        test("hls.js retries a segment only when the server's failure can pass") {
            retriesSegmentAfter(500) shouldBe false
            retriesSegmentAfter(501) shouldBe false
            retriesSegmentAfter(503) shouldBe true
            retriesSegmentAfter(504) shouldBe true
            // Everything else is hls.js's own call: it already declines 4xx and retries timeouts.
            retriesSegmentAfter(null) shouldBe true
            retriesSegmentAfter(404) shouldBe true
        }
    })
