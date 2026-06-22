package com.calypsan.listenup.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [audioContentType] — the format→MIME mapping that lets a native
 * player identify an extension-less streamed audio URL. The `.m4b` case is the
 * regression: Ktor has no `.m4b` mime entry, so the route must map it explicitly
 * to `audio/mp4` rather than fall back to `application/octet-stream`.
 */
class AudioContentTypeTest :
    FunSpec({

        test("m4b maps to audio/mp4 (the iOS streaming fix)") {
            audioContentType("m4b").toString() shouldBe "audio/mp4"
        }

        test("common formats map to their audio MIME types") {
            audioContentType("m4a").toString() shouldBe "audio/mp4"
            audioContentType("mp3").toString() shouldBe "audio/mpeg"
            audioContentType("flac").toString() shouldBe "audio/flac"
            audioContentType("opus").toString() shouldBe "audio/opus"
            audioContentType("ogg").toString() shouldBe "audio/ogg"
        }

        test("matching is case-insensitive") {
            audioContentType("M4B").toString() shouldBe "audio/mp4"
            audioContentType("Mp3").toString() shouldBe "audio/mpeg"
        }

        test("an unknown format falls back to octet-stream") {
            audioContentType("xyz").toString() shouldBe "application/octet-stream"
        }
    })
