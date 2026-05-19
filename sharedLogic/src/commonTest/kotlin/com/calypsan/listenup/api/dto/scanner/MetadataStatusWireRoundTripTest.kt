package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins that every [MetadataStatus] variant round-trips through the
 * canonical [contractJson] using the polymorphic `MetadataStatus.serializer()`.
 *
 * `MetadataStatus` is sealed and the embedded `AudioMetadataError` variant
 * itself nests a sealed `AppError` subtype — both layers exercise the
 * sealed-hierarchy auto-resolution.
 */
class MetadataStatusWireRoundTripTest :
    FunSpec({
        val json = contractJson

        test("Available round-trips") {
            val status: MetadataStatus = MetadataStatus.Available
            val decoded = json.decodeFromString(MetadataStatus.serializer(), json.encodeToString(MetadataStatus.serializer(), status))
            decoded shouldBe status
        }

        test("UnsupportedFormat with named format round-trips") {
            val status: MetadataStatus = MetadataStatus.UnsupportedFormat(format = AudioFormat.Flac)
            val decoded = json.decodeFromString(MetadataStatus.serializer(), json.encodeToString(MetadataStatus.serializer(), status))
            decoded shouldBe status
        }

        test("UnsupportedFormat with null format round-trips") {
            val status: MetadataStatus = MetadataStatus.UnsupportedFormat(format = null)
            val decoded = json.decodeFromString(MetadataStatus.serializer(), json.encodeToString(MetadataStatus.serializer(), status))
            decoded shouldBe status
        }

        test("ParseError wrapping AudioMetadataError round-trips") {
            val status: MetadataStatus =
                MetadataStatus.ParseError(
                    error =
                        AudioMetadataError.CorruptHeader(
                            pathString = "/lib/bad.mp3",
                            format = AudioFormat.Mp3,
                            offset = 42,
                            expected = "ID3",
                            correlationId = "cor-1",
                        ),
                )
            val decoded = json.decodeFromString(MetadataStatus.serializer(), json.encodeToString(MetadataStatus.serializer(), status))
            decoded shouldBe status
        }
    })
