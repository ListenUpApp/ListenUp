package com.calypsan.listenup.server.transcode

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The counter exists to catch an encoder that produced fewer frames than the playlist promised, so
 * its own edge cases have to be exact rather than approximate — a parser that silently gives up
 * halfway would report a short segment as short for the wrong reason.
 */
class AdtsFramesTest :
    FunSpec({

        test("counts one frame per ADTS packet") {
            val stream = AdtsFixtures.frame(payload = 100) + AdtsFixtures.frame(payload = 120) + AdtsFixtures.frame(payload = 90)

            AdtsFrames.countFrames(stream) shouldBe 3
        }

        test("an empty stream has no frames") {
            AdtsFrames.countFrames(ByteArray(0)) shouldBe 0
        }

        // The header can declare several raw data blocks in one packet; each is a full 1024 samples,
        // so counting packets instead of blocks would under-report the audio actually present.
        test("counts every raw data block, not just the packets carrying them") {
            val stream = AdtsFixtures.frame(payload = 100, rawDataBlocks = 3)

            AdtsFrames.countFrames(stream) shouldBe 3
        }

        // A truncated tail is exactly what a segment served mid-write looks like. Answering with a
        // count would let a partial segment pass as whole.
        test("a truncated final frame is not a count") {
            val stream = AdtsFixtures.frame(payload = 100) + AdtsFixtures.frame(payload = 100).copyOfRange(0, 40)

            AdtsFrames.countFrames(stream) shouldBe null
        }

        test("bytes that are not ADTS at all are not a count") {
            AdtsFrames.countFrames(ByteArray(64) { 0x42 }) shouldBe null
        }

        test("a frame declaring a length shorter than its own header is refused") {
            val broken = AdtsFixtures.frame(payload = 100).copyOf()
            // aac_frame_length = 3, which cannot even cover the 7-byte header.
            broken[3] = (broken[3].toInt() and 0xFC).toByte()
            broken[4] = 0
            broken[5] = (3 shl 5).toByte()

            AdtsFrames.countFrames(broken) shouldBe null
        }
    })
