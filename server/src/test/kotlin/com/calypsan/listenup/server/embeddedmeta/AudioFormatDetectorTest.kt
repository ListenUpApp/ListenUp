package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AudioFormatDetectorTest : FunSpec({
    val detector = AudioFormatDetector()

    test("detects ID3v2-prefixed MP3") {
        val bytes = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) + ByteArray(6)
        detector.detect(bytes) shouldBe AudioFormat.Mp3
    }

    test("detects bare MP3 sync word (no ID3v2 prefix)") {
        // 0xFF 0xFB = MPEG audio frame sync.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(14)
        detector.detect(bytes) shouldBe AudioFormat.Mp3
    }

    test("detects FLAC by fLaC magic") {
        val bytes = byteArrayOf(0x66, 0x4C, 0x61, 0x43) + ByteArray(12)
        detector.detect(bytes) shouldBe AudioFormat.Flac
    }

    test("detects OggS magic — codec disambiguation later by the parser") {
        val bytes = byteArrayOf(0x4F, 0x67, 0x67, 0x53) + ByteArray(12)
        detector.detect(bytes) shouldBe AudioFormat.Ogg
    }

    test("detects MP4 by ftyp atom at offset 4") {
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70) + ByteArray(8)
        detector.detect(bytes) shouldBe AudioFormat.Mp4
    }

    test("returns null for unrecognized magic (e.g. RIFF/WAV)") {
        val bytes = byteArrayOf(0x52, 0x49, 0x46, 0x46) + ByteArray(12)
        detector.detect(bytes) shouldBe null
    }
})
