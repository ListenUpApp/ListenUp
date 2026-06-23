package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.AudioFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookDetailFormattingTest :
    FunSpec({
        fun file(codec: String, size: Long, duration: Long) =
            AudioFile(id = "x", index = 0, filename = "f.$codec", format = codec, codec = codec, duration = duration, size = size)

        test("audioFormatSummary derives codec (upper-cased) and approx bitrate") {
            // 1,000,000 bytes over 60,000 ms → 1_000_000*8 / 60 / 1000 ≈ 133 kbps
            val f = audioFormatSummary(listOf(file("aac", 1_000_000, 60_000)))
            f?.codec shouldBe "AAC"
            f?.approxBitrateKbps shouldBe 133
            f?.displayLabel() shouldBe "AAC · ~133 kbps"
        }

        test("audioFormatSummary returns null when there are no files") {
            audioFormatSummary(emptyList()) shouldBe null
        }

        test("audioFormatSummary omits bitrate (codec only) when total duration is zero") {
            val f = audioFormatSummary(listOf(file("mp3", 1000, 0)))
            f?.codec shouldBe "MP3"
            f?.approxBitrateKbps shouldBe null
            f?.displayLabel() shouldBe "MP3"
        }

        test("audioFormatSummary picks the most common codec across files") {
            val f = audioFormatSummary(listOf(file("aac", 100, 1000), file("aac", 100, 1000), file("mp3", 100, 1000)))
            f?.codec shouldBe "AAC"
        }

        test("languageDisplayName maps known codes and falls back to the upper-cased code") {
            languageDisplayName("en") shouldBe "English"
            languageDisplayName("EN") shouldBe "English"
            languageDisplayName("xx") shouldBe "XX"
        }
    })
