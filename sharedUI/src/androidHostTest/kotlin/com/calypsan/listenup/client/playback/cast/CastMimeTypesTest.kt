package com.calypsan.listenup.client.playback.cast

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CastMimeTypesTest :
    FunSpec({
        test("maps mp4-family formats to audio/mp4") {
            listOf("m4b", "m4a", "mp4", "m4p").forEach { castMimeType(it) shouldBe "audio/mp4" }
        }
        test("maps raw aac to audio/aac") { castMimeType("aac") shouldBe "audio/aac" }
        test("maps mp3 to audio/mpeg") { castMimeType("mp3") shouldBe "audio/mpeg" }
        test("maps flac/wav/ogg/opus/webm") {
            castMimeType("flac") shouldBe "audio/flac"
            castMimeType("wav") shouldBe "audio/wav"
            castMimeType("wave") shouldBe "audio/wav"
            castMimeType("ogg") shouldBe "audio/ogg"
            castMimeType("oga") shouldBe "audio/ogg"
            castMimeType("opus") shouldBe "audio/ogg"
            castMimeType("webm") shouldBe "audio/webm"
        }
        test("is case-insensitive") { castMimeType("MP3") shouldBe "audio/mpeg" }
        test("returns null for Cast-incompatible formats") {
            castMimeType("wma") shouldBe null
            castMimeType("aiff") shouldBe null
        }
    })
