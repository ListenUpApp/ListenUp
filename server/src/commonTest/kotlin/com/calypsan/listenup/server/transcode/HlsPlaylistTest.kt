package com.calypsan.listenup.server.transcode

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs

/**
 * ⛔ The drift test is the reason this file exists as its own unit.
 *
 * FFmpeg cuts AAC on 1024-sample frame boundaries, so a segment asked to be 10s is really 10.0078s
 * at 44.1 kHz. Declaring a round 10s instead compounds that error once per segment: the reference
 * library's longest book is 92.7 hours — 33,346 segments — where 7.8ms each becomes about four
 * minutes of drift by the end, and every seek and every position written back is wrong by it.
 */
class HlsPlaylistTest :
    FunSpec({

        test("segment length is frame-aligned upward, not the requested round number") {
            val plan = HlsPlaylist.plan(durationMs = 60_000, sampleRate = 44_100, targetSeconds = 10)

            // ceil(10 * 44100 / 1024) = 431 frames -> 431 * 1024 / 44100
            plan.segmentSeconds shouldBe 431.0 * 1024 / 44_100
        }

        test("a different sample rate gives a different aligned length") {
            val plan = HlsPlaylist.plan(durationMs = 60_000, sampleRate = 22_050, targetSeconds = 10)

            plan.segmentSeconds shouldBe 216.0 * 1024 / 22_050
        }

        // The whole point: declared timeline must equal real timeline, at the end of a long book.
        test("declared durations still sum to the book over 92 hours") {
            val durationMs = 92L * 60 * 60 * 1000 + 42L * 60 * 1000
            val plan = HlsPlaylist.plan(durationMs, sampleRate = 44_100, targetSeconds = 10)

            val declared = plan.segmentDurations.sum()
            abs(declared - durationMs / 1000.0) shouldBeLessThan 0.001
        }

        test("the final segment carries the remainder") {
            val plan = HlsPlaylist.plan(durationMs = 25_000, sampleRate = 44_100, targetSeconds = 10)

            plan.segmentDurations.size shouldBe 3
            plan.segmentDurations.last() shouldBeLessThan plan.segmentSeconds
        }

        // A duration landing exactly on a segment boundary must not declare a zero-length tail:
        // FFmpeg would never write that segment, so the player would 404 on the last one. Whole
        // milliseconds only divide evenly when the segment count is a multiple of 441, so 33,075
        // segments — 91.95 hours, near the longest book in the reference library — is the smallest
        // case worth pinning rather than the arbitrary small one it is tempting to write.
        test("a duration that divides evenly declares no empty trailing segment") {
            val plan = HlsPlaylist.plan(durationMs = 331_008_000, sampleRate = 44_100, targetSeconds = 10)

            plan.segmentDurations.size shouldBe 33_075
        }

        test("renders a VOD playlist a player will accept") {
            val plan = HlsPlaylist.plan(durationMs = 25_000, sampleRate = 44_100, targetSeconds = 10)

            val text = HlsPlaylist.render(plan, segmentUrl = { i -> "seg/$i.aac?sig=x" })

            text shouldContain "#EXTM3U"
            text shouldContain "#EXT-X-PLAYLIST-TYPE:VOD"
            text shouldContain "#EXT-X-TARGETDURATION:11"
            text shouldContain "#EXTINF:10.007800,"
            text shouldContain "seg/0.aac?sig=x"
            text shouldContain "#EXT-X-ENDLIST"
        }

        // A file with no recorded sample rate is common in a real library (257 of 1455 rows).
        test("an unknown sample rate falls back to the dominant one rather than failing") {
            val plan = HlsPlaylist.plan(durationMs = 25_000, sampleRate = null, targetSeconds = 10)

            plan.segmentSeconds shouldBe 431.0 * 1024 / 44_100
        }
    })
