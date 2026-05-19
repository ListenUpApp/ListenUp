package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips the new scan-summary counters through [contractJson]. Pins
 * that defaults survive (older clients with bare `ScanResultSummary` keep
 * working), that named [AudioFormat] keys serialise cleanly, and that
 * the embedded counters slot inside [ScanResultSummary] produces stable
 * wire shape.
 */
class EmbeddedScanCountersWireRoundTripTest :
    FunSpec({
        val json = contractJson

        test("UnsupportedFormatCount round-trips") {
            val count = UnsupportedFormatCount(format = AudioFormat.Flac, count = 12)
            val decoded = json.decodeFromString(UnsupportedFormatCount.serializer(), json.encodeToString(UnsupportedFormatCount.serializer(), count))
            decoded shouldBe count
        }

        test("EmbeddedScanCounters with all defaults round-trips") {
            val counters = EmbeddedScanCounters()
            val decoded = json.decodeFromString(EmbeddedScanCounters.serializer(), json.encodeToString(EmbeddedScanCounters.serializer(), counters))
            decoded shouldBe counters
        }

        test("EmbeddedScanCounters with populated fields round-trips") {
            val counters =
                EmbeddedScanCounters(
                    parsed = 42,
                    unsupported = 15,
                    parseErrors = 2,
                    withChapters = 30,
                    withArtwork = 38,
                    unsupportedFormats =
                        listOf(
                            UnsupportedFormatCount(format = AudioFormat.Flac, count = 12),
                            UnsupportedFormatCount(format = AudioFormat.Opus, count = 1),
                        ),
                    unrecognisedMagic = 2,
                )
            val decoded = json.decodeFromString(EmbeddedScanCounters.serializer(), json.encodeToString(EmbeddedScanCounters.serializer(), counters))
            decoded shouldBe counters
        }

        test("ScanResultSummary with embedded counters round-trips") {
            val summary =
                ScanResultSummary(
                    correlationId = "corr-1",
                    totalBooks = 50,
                    added = 5,
                    modified = 1,
                    removed = 0,
                    moved = 0,
                    errors = 0,
                    durationMs = 1234,
                    filesWalked = 200,
                    embedded =
                        EmbeddedScanCounters(
                            parsed = 35,
                            unsupported = 13,
                            parseErrors = 2,
                            withChapters = 20,
                            withArtwork = 30,
                            unsupportedFormats = listOf(UnsupportedFormatCount(AudioFormat.Flac, 13)),
                            unrecognisedMagic = 0,
                        ),
                )
            val decoded = json.decodeFromString(ScanResultSummary.serializer(), json.encodeToString(ScanResultSummary.serializer(), summary))
            decoded shouldBe summary
        }
    })
