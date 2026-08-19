package com.calypsan.listenup.server.transcode

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The argv is pure, so the whole decision table is a unit test. What it has to get right is which
 * decoder runs: FFmpeg's own USAC decoder silently drops about a quarter of an Audible xHE-AAC book
 * while exiting 0, so those sources must be routed through Fraunhofer FDK instead.
 */
class TranscodeCommandTest :
    FunSpec({

        test("an AAC-LC source is one FFmpeg invocation — no decoder detour is needed") {
            val stages = commandsFor(codec = "aac", profile = "lc")

            stages.shouldNotBeNullAndHaveSize(1)
            stages!!.single().shouldContainInOrder("-i", SOURCE)
            stages.single().last() shouldBe OUTPUT_PATTERN
        }

        // ⛔ The reason this whole class exists. Measured on a real library file: FFmpeg's native
        // decoder refused 14,464 of 63,559 packets and still exited 0.
        test("an xHE-AAC source is decoded by FDK through a three-stage pipeline") {
            val stages = commandsFor(codec = "aac", profile = "xhe")

            stages.shouldNotBeNullAndHaveSize(3)
            // Stage 1 seeks and demuxes only — `-c:a copy` never invokes the broken decoder.
            stages!![0].shouldContainInOrder("-c:a", "copy")
            stages[0].shouldContainInOrder("-f", "matroska")
            stages[0].last() shouldBe "-"
            // Stage 2 is the only place the audio is actually decoded.
            stages[1].joinToString(" ") shouldContain "fdkaacdec"
            // Stage 3 re-encodes from raw PCM and does the segmenting.
            stages[2].shouldContainInOrder("-f", "s16le")
            stages[2].last() shouldBe OUTPUT_PATTERN
        }

        test("the seek happens once, on the stage that demuxes") {
            val stages = commandsFor(codec = "aac", profile = "xhe", startSeconds = 400.312018)!!

            stages[0].shouldContainInOrder("-ss", "400.312018")
            stages.drop(1).forEach { stage -> stage.contains("-ss") shouldBe false }
        }

        // Without a decoder we would produce audio that is quietly missing a fifth of the book.
        // Refusing is the only honest answer.
        test("an xHE-AAC source with no FDK decoder available yields no command at all") {
            commandsFor(codec = "aac", profile = "xhe", decoderPath = null).shouldBeNull()
        }

        test("a source FFmpeg can decode still works when no FDK decoder is installed") {
            commandsFor(codec = "aac", profile = "lc", decoderPath = null).shouldNotBeNullAndHaveSize(1)
        }

        // HlsPlaylist computes the segment length from the SOURCE rate; an output at any other rate
        // makes every declared #EXTINF describe a file we did not write.
        test("the encode never resamples, on either path") {
            commandsFor(codec = "aac", profile = "lc")!!.single().shouldContainInOrder("-ar", "44100")
            commandsFor(codec = "aac", profile = "xhe")!![2].shouldContainInOrder("-ar", "44100")
        }

        test("both paths declare the same frame-aligned segment length and run list") {
            val direct = commandsFor(codec = "aac", profile = "lc")!!.single()
            val viaFdk = commandsFor(codec = "aac", profile = "xhe")!![2]

            for (stage in listOf(direct, viaFdk)) {
                stage.shouldContainInOrder("-segment_time", "10.007800")
                stage.shouldContainInOrder("-segment_start_number", "7")
                stage.shouldContainInOrder("-segment_list", RUN_LIST)
            }
        }

        test("a mono source is decoded as mono rather than assumed stereo") {
            val stages = commandsFor(codec = "aac", profile = "xhe", channels = 1)!!

            stages[1].joinToString(" ") shouldContain "channels=1"
            stages[2].shouldContainInOrder("-ac", "1")
        }
    })

private const val SOURCE = "/library/book/01.m4b"
private const val OUTPUT_PATTERN = "/cache/seg%05d.aac"
private const val RUN_LIST = "/cache/runs/from00007.list"
private const val FFMPEG = "/usr/local/bin/ffmpeg"
private const val DECODER = "/usr/local/bin/gst-launch-1.0"

private fun commandsFor(
    codec: String,
    profile: String?,
    decoderPath: String? = DECODER,
    startSeconds: Double = 70.054603,
    channels: Int = 2,
): List<List<String>>? =
    TranscodeCommand.forSession(
        ffmpegPath = FFMPEG,
        decoderPath = decoderPath,
        session =
            TranscodeSession(
                bookId = "b1",
                fileId = "f1",
                sourcePath = SOURCE,
                sampleRate = 44_100,
                durationMs = 600_000L,
                startSegment = 7,
                codec = codec,
                codecProfile = profile,
                channels = channels,
            ),
        startSeconds = startSeconds,
        plan = HlsPlaylist.plan(600_000L, 44_100, 10),
        outputPattern = OUTPUT_PATTERN,
        runListPath = RUN_LIST,
        bitrateKbps = 64,
    )

private fun List<List<String>>?.shouldNotBeNullAndHaveSize(expected: Int) {
    (this?.size ?: -1) shouldBe expected
}
