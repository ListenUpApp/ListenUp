package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.AudioFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookDetailFormattingTest :
    FunSpec({
        fun file(
            codec: String,
            size: Long,
            duration: Long,
        ) = AudioFile(id = "x", index = 0, filename = "f.$codec", format = codec, codec = codec, duration = duration, size = size)

        test("audioFormatSummary derives codec (upper-cased) and approx bitrate") {
            // 1,000,000 bytes over 60,000 ms → 1_000_000*8 / 60 / 1000 ≈ 133 kbps
            val f = audioFormatSummary(listOf(file("aac", 1_000_000, 60_000)))
            f?.codec shouldBe "AAC"
            f?.approxBitrateKbps shouldBe 133
        }

        test("audioFormatSummary returns null when there are no files") {
            audioFormatSummary(emptyList()) shouldBe null
        }

        test("audioFormatSummary omits bitrate (codec only) when total duration is zero") {
            val f = audioFormatSummary(listOf(file("mp3", 1000, 0)))
            f?.codec shouldBe "MP3"
            f?.approxBitrateKbps shouldBe null
        }

        test("audioFormatSummary picks the most common codec across files") {
            val f = audioFormatSummary(listOf(file("aac", 100, 1000), file("aac", 100, 1000), file("mp3", 100, 1000)))
            f?.codec shouldBe "AAC"
        }

        test("audioFormatSummary shows bitrate alone when codec is blank") {
            val f = audioFormatSummary(listOf(file("", 1_000_000, 60_000)))
            f?.codec shouldBe ""
            f?.approxBitrateKbps shouldBe 133
        }

        test("audioFormatSummary returns null when codec blank and no bitrate") {
            audioFormatSummary(listOf(file("", 1000, 0))) shouldBe null
        }

        test("audioFormatSummary prefers a real codec over blank entries") {
            val f = audioFormatSummary(listOf(file("", 100, 1000), file("aac", 100, 1000)))
            f?.codec shouldBe "AAC"
        }

        test("audioFormatDisplay derives rows from the primary file; bitrate falls back to approx") {
            val atmos =
                audioFormatDisplay(
                    listOf(
                        file("ac4", 1_000_000, 60_000)
                            .copy(spatial = "atmos", bitrate = 320_000, sampleRate = 48_000, channels = 6),
                    ),
                )
            atmos.format shouldBe "Dolby Atmos"
            atmos.bitrate shouldBe "320 kbps"
            atmos.sampleRate shouldBe "48 kHz"
            atmos.channels shouldBe "5.1"

            // No exact bitrate → falls back to the size/duration approximation; no rate/channels.
            val approx = audioFormatDisplay(listOf(file("aac", 1_000_000, 60_000)))
            approx.format shouldBe "AAC"
            approx.bitrate shouldBe "~133 kbps"
            approx.sampleRate shouldBe null
            approx.channels shouldBe null

            audioFormatDisplay(emptyList()).format shouldBe null
        }

        test("languageDisplayName maps known codes and falls back to the upper-cased code") {
            languageDisplayName("en") shouldBe "English"
            languageDisplayName("EN") shouldBe "English"
            languageDisplayName("xx") shouldBe "XX"
        }

        test("audioFormatIdentity maps spatial atmos to Dolby Atmos regardless of codec") {
            audioFormatIdentity("ac4", null, "atmos") shouldBe "Dolby Atmos"
            audioFormatIdentity("eac3", null, "ATMOS") shouldBe "Dolby Atmos"
        }

        test("audioFormatIdentity maps AAC by profile") {
            audioFormatIdentity("aac", "xhe", null) shouldBe "xHE-AAC"
            audioFormatIdentity("aac", "hev2", null) shouldBe "HE-AAC v2"
            audioFormatIdentity("aac", "he", null) shouldBe "HE-AAC"
            audioFormatIdentity("aac", "lc", null) shouldBe "AAC"
            audioFormatIdentity("aac", null, null) shouldBe "AAC"
        }

        test("audioFormatIdentity maps known codecs and upper-cases unknowns") {
            audioFormatIdentity("ac4", null, null) shouldBe "AC-4"
            audioFormatIdentity("eac3", null, null) shouldBe "E-AC-3"
            audioFormatIdentity("mp3", null, null) shouldBe "MP3"
            audioFormatIdentity("flac", null, null) shouldBe "FLAC"
            audioFormatIdentity("opus", null, null) shouldBe "Opus"
            audioFormatIdentity("alac", null, null) shouldBe "ALAC"
            audioFormatIdentity("vorbis", null, null) shouldBe "Vorbis"
            audioFormatIdentity("dts", null, null) shouldBe "DTS"
        }

        test("audioFormatIdentity is null when codec blank and not spatial") {
            audioFormatIdentity("", null, null) shouldBe null
            audioFormatIdentity("  ", null, "") shouldBe null
        }

        test("bitrateLabel formats bits per second as kbps, null when absent or non-positive") {
            bitrateLabel(320_000) shouldBe "320 kbps"
            bitrateLabel(128_000) shouldBe "128 kbps"
            bitrateLabel(null) shouldBe null
            bitrateLabel(0) shouldBe null
        }

        test("sampleRateLabel formats hertz as kHz") {
            sampleRateLabel(48_000) shouldBe "48 kHz"
            sampleRateLabel(44_100) shouldBe "44.1 kHz"
            sampleRateLabel(null) shouldBe null
            sampleRateLabel(0) shouldBe null
        }

        test("channelsLabel names common layouts, else N ch") {
            channelsLabel(1) shouldBe "Mono"
            channelsLabel(2) shouldBe "Stereo"
            channelsLabel(6) shouldBe "5.1"
            channelsLabel(8) shouldBe "7.1"
            channelsLabel(3) shouldBe "3 ch"
            channelsLabel(null) shouldBe null
            channelsLabel(0) shouldBe null
        }
    })
