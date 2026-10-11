package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins that every `AudioMetadataError` subtype round-trips through the
 * canonical [contractJson] using the polymorphic `AppError.serializer()`.
 *
 * `AppError` is a sealed interface; kotlinx.serialization auto-resolves
 * its subtypes from the sealed hierarchy, so no explicit module
 * registration is required.
 */
class AudioMetadataErrorWireRoundTripTest :
    FunSpec({
        val json = contractJson

        test("UnsupportedFormat round-trips") {
            val err: AppError =
                AudioMetadataError.UnsupportedFormat(
                    pathString = "/lib/foo.flac",
                    detectedMagic = "fLaC",
                    format = AudioFormat.Flac,
                    correlationId = "cor-1",
                )
            val decoded = json.decodeFromString(AppError.serializer(), json.encodeToString(AppError.serializer(), err))
            decoded shouldBe err
        }

        test("CorruptHeader round-trips") {
            val err: AppError =
                AudioMetadataError.CorruptHeader(
                    pathString = "/lib/bad.mp3",
                    format = AudioFormat.Mp3,
                    offset = 42,
                    expected = "ID3",
                    correlationId = "cor-2",
                )
            val decoded = json.decodeFromString(AppError.serializer(), json.encodeToString(AppError.serializer(), err))
            decoded shouldBe err
        }

        test("TruncatedStream round-trips") {
            val err: AppError =
                AudioMetadataError.TruncatedStream(
                    pathString = "/lib/short.flac",
                    format = AudioFormat.Flac,
                    expectedBytes = 1024,
                    actualBytes = 512,
                    correlationId = "cor-3",
                )
            val decoded = json.decodeFromString(AppError.serializer(), json.encodeToString(AppError.serializer(), err))
            decoded shouldBe err
        }

        test("IoError round-trips") {
            val err: AppError =
                AudioMetadataError.IoError(
                    pathString = "/lib/locked.m4b",
                    ioMessage = "permission denied",
                    correlationId = "cor-4",
                )
            val decoded = json.decodeFromString(AppError.serializer(), json.encodeToString(AppError.serializer(), err))
            decoded shouldBe err
        }
    })
