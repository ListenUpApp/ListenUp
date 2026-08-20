package com.calypsan.listenup.web.playback

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
    })
