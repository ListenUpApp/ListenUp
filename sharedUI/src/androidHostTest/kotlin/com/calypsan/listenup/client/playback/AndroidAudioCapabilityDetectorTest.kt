package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [AndroidAudioCapabilityDetector.mimeTypeToCodec].
 *
 * The mapping function is pure: String in → codec-name-or-null out. All
 * [android.media.MediaFormat] MIME-type constants used in the branches are
 * compile-time [ConstantValue] attributes that the Kotlin compiler inlines into
 * the bytecode `when` expression, so no Android runtime or Robolectric is
 * needed — a plain Kotest FunSpec on the JVM is sufficient.
 *
 * Actual constant values verified against android-37.0/android.jar via javap.
 */
class AndroidAudioCapabilityDetectorTest :
    FunSpec({
        val detector = AndroidAudioCapabilityDetector()

        // AAC — MIMETYPE_AUDIO_AAC = "audio/mp4a-latm"
        test("audio/mp4a-latm maps to aac") {
            detector.mimeTypeToCodec("audio/mp4a-latm") shouldBe "aac"
        }

        // AAC alias used in the when branch alongside the constant
        test("audio/mp4a-latm alias maps to aac") {
            detector.mimeTypeToCodec("audio/mp4a-latm") shouldBe "aac"
        }

        // MP3 — MIMETYPE_AUDIO_MPEG = "audio/mpeg"
        test("audio/mpeg maps to mp3") {
            detector.mimeTypeToCodec("audio/mpeg") shouldBe "mp3"
        }

        // MP3 alias
        test("audio/mp3 alias maps to mp3") {
            detector.mimeTypeToCodec("audio/mp3") shouldBe "mp3"
        }

        // Opus — MIMETYPE_AUDIO_OPUS = "audio/opus"
        test("audio/opus maps to opus") {
            detector.mimeTypeToCodec("audio/opus") shouldBe "opus"
        }

        // Vorbis — MIMETYPE_AUDIO_VORBIS = "audio/vorbis"
        test("audio/vorbis maps to vorbis") {
            detector.mimeTypeToCodec("audio/vorbis") shouldBe "vorbis"
        }

        // FLAC — MIMETYPE_AUDIO_FLAC = "audio/flac"
        test("audio/flac maps to flac") {
            detector.mimeTypeToCodec("audio/flac") shouldBe "flac"
        }

        // PCM/RAW — MIMETYPE_AUDIO_RAW = "audio/raw"
        test("audio/raw maps to pcm") {
            detector.mimeTypeToCodec("audio/raw") shouldBe "pcm"
        }

        // AC-3 — MIMETYPE_AUDIO_AC3 = "audio/ac3"
        test("audio/ac3 maps to ac3") {
            detector.mimeTypeToCodec("audio/ac3") shouldBe "ac3"
        }

        // E-AC-3 — MIMETYPE_AUDIO_EAC3 = "audio/eac3"
        test("audio/eac3 maps to eac3") {
            detector.mimeTypeToCodec("audio/eac3") shouldBe "eac3"
        }

        // DTS variants (literal strings in the when branch, no MediaFormat constant)
        test("audio/vnd.dts maps to dts") {
            detector.mimeTypeToCodec("audio/vnd.dts") shouldBe "dts"
        }

        test("audio/vnd.dts.hd maps to dts") {
            detector.mimeTypeToCodec("audio/vnd.dts.hd") shouldBe "dts"
        }

        // Dolby TrueHD — MIMETYPE_AUDIO_DOLBY_TRUEHD = "audio/vnd.dolby.mlp"
        test("audio/vnd.dolby.mlp maps to truehd") {
            detector.mimeTypeToCodec("audio/vnd.dolby.mlp") shouldBe "truehd"
        }

        // AMR — MIMETYPE_AUDIO_AMR_NB = "audio/3gpp"
        test("audio/3gpp (AMR-NB) maps to amr") {
            detector.mimeTypeToCodec("audio/3gpp") shouldBe "amr"
        }

        // AMR-WB — MIMETYPE_AUDIO_AMR_WB = "audio/amr-wb"
        test("audio/amr-wb (AMR-WB) maps to amr") {
            detector.mimeTypeToCodec("audio/amr-wb") shouldBe "amr"
        }

        // GSM types that are explicitly mapped to null (not reported)
        // MIMETYPE_AUDIO_MSGSM = "audio/gsm"
        test("audio/gsm (GSM) returns null") {
            detector.mimeTypeToCodec("audio/gsm") shouldBe null
        }

        // MIMETYPE_AUDIO_G711_ALAW = "audio/g711-alaw"
        test("audio/g711-alaw returns null") {
            detector.mimeTypeToCodec("audio/g711-alaw") shouldBe null
        }

        // MIMETYPE_AUDIO_G711_MLAW = "audio/g711-mlaw"
        test("audio/g711-mlaw returns null") {
            detector.mimeTypeToCodec("audio/g711-mlaw") shouldBe null
        }

        // Unknown MIME type falls through to else → null
        test("unknown mime type returns null") {
            detector.mimeTypeToCodec("audio/unknown-codec-xyz") shouldBe null
        }

        test("completely unrelated mime type returns null") {
            detector.mimeTypeToCodec("video/mp4") shouldBe null
        }

        test("empty string returns null") {
            detector.mimeTypeToCodec("") shouldBe null
        }

        // Case-insensitivity: the when expression lowercases the input first
        test("uppercase MIME type is handled case-insensitively") {
            detector.mimeTypeToCodec("AUDIO/MPEG") shouldBe "mp3"
        }

        test("mixed-case MIME type is handled case-insensitively") {
            detector.mimeTypeToCodec("Audio/Flac") shouldBe "flac"
        }

        test("uppercase AAC is handled case-insensitively") {
            detector.mimeTypeToCodec("AUDIO/MP4A-LATM") shouldBe "aac"
        }
    })
