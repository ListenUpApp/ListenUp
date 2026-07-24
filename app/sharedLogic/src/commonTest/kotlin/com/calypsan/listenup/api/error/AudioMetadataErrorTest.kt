package com.calypsan.listenup.api.error

import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

class AudioMetadataErrorTest :
    FunSpec({
        val json = Json {}

        test("UnsupportedFormat presentation fields") {
            val err =
                AudioMetadataError.UnsupportedFormat(
                    pathString = "/lib/foo.wav",
                    detectedMagic = "RIFF",
                )
            err.code shouldBe "AUDIO_META_UNSUPPORTED_FORMAT"
            err.isRetryable shouldBe false
            err.format shouldBe null
        }

        test("UnsupportedFormat round-trips with AudioFormat populated") {
            val err =
                AudioMetadataError.UnsupportedFormat(
                    pathString = "/lib/song.flac",
                    detectedMagic = "fLaC",
                    format = AudioFormat.Flac,
                )
            val decoded =
                json.decodeFromString(
                    AudioMetadataError.UnsupportedFormat.serializer(),
                    json.encodeToString(AudioMetadataError.UnsupportedFormat.serializer(), err),
                )
            decoded.format shouldBe AudioFormat.Flac
            decoded.detectedMagic shouldBe "fLaC"
            decoded.pathString shouldBe "/lib/song.flac"
        }

        test("CorruptHeader presentation fields") {
            val err =
                AudioMetadataError.CorruptHeader(
                    pathString = "/lib/bad.mp3",
                    format = AudioFormat.Mp3,
                    offset = 42,
                    expected = "ID3",
                )
            err.code shouldBe "AUDIO_META_CORRUPT_HEADER"
            err.isRetryable shouldBe false
        }

        test("TruncatedStream presentation fields") {
            val err =
                AudioMetadataError.TruncatedStream(
                    pathString = "/lib/short.flac",
                    format = AudioFormat.Flac,
                    expectedBytes = 1024,
                    actualBytes = 512,
                )
            err.code shouldBe "AUDIO_META_TRUNCATED_STREAM"
            err.isRetryable shouldBe false
        }

        test("IoError is retryable") {
            val err =
                AudioMetadataError.IoError(
                    pathString = "/lib/locked.m4b",
                    ioMessage = "permission denied",
                )
            err.code shouldBe "AUDIO_META_IO_ERROR"
            err.isRetryable shouldBe true
        }

        test("AudioMetadataError round-trips through AppError serializer") {
            val err: AppError =
                AudioMetadataError.CorruptHeader(
                    pathString = "/lib/bad.mp3",
                    format = AudioFormat.Mp3,
                    offset = 42,
                    expected = "ID3",
                )
            val encoded = json.encodeToString(AppError.serializer(), err)
            val decoded = json.decodeFromString(AppError.serializer(), encoded)
            decoded.shouldBeInstanceOf<AudioMetadataError.CorruptHeader>()
            decoded.code shouldBe "AUDIO_META_CORRUPT_HEADER"
        }
    })
