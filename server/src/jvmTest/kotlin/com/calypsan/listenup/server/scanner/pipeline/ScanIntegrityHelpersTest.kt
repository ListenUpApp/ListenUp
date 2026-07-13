package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.domain.embeddedmeta.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Duration-integrity and embedded-chapter-clamp helpers (A9). A book that yields no usable duration,
 * or a multi-file book missing a track length, must be flagged for the operator instead of ingesting
 * silently; embedded chapters that overrun the parsed duration must be clamped or dropped so a
 * mistagged file can't push later chapters off the end of the book.
 */
class ScanIntegrityHelpersTest :
    FunSpec({

        context("durationScanWarning") {
            test("no audio files → no warning") {
                durationScanWarning(bookDurationMs = 0L, trackDurations = emptyList()) shouldBe false
            }

            test("single-file book with a real duration → no warning") {
                // Single-file books leave TrackEntry.durationMs null; the book duration comes from embedded.
                durationScanWarning(bookDurationMs = 1_000L, trackDurations = listOf(null)) shouldBe false
            }

            test("single-file book that parsed to zero duration → warning") {
                durationScanWarning(bookDurationMs = 0L, trackDurations = listOf(null)) shouldBe true
            }

            test("multi-file book with every track length present → no warning") {
                durationScanWarning(bookDurationMs = 3_000L, trackDurations = listOf(1_000L, 2_000L)) shouldBe false
            }

            test("multi-file book missing a middle track's duration → warning") {
                durationScanWarning(bookDurationMs = 3_000L, trackDurations = listOf(1_000L, null, 2_000L)) shouldBe true
            }

            test("multi-file book with a zero-length track → warning") {
                durationScanWarning(bookDurationMs = 3_000L, trackDurations = listOf(1_000L, 0L, 2_000L)) shouldBe true
            }
        }

        context("groupingScanWarning") {
            test("single format, single album → no warning") {
                groupingScanWarning(trackExtensions = listOf("mp3", "mp3"), distinctAlbumTags = 1) shouldBe false
            }

            test("mixed m4b + mp3 formats in one book → warning") {
                groupingScanWarning(trackExtensions = listOf("m4b", "mp3"), distinctAlbumTags = 1) shouldBe true
            }

            test("two distinct album tags in one candidate → warning") {
                groupingScanWarning(trackExtensions = listOf("mp3", "mp3"), distinctAlbumTags = 2) shouldBe true
            }

            test("case-insensitive format comparison — MP3 and mp3 are one format") {
                groupingScanWarning(trackExtensions = listOf("MP3", "mp3"), distinctAlbumTags = 1) shouldBe false
            }
        }

        context("clampEmbeddedChapters") {
            fun ch(
                index: Int,
                start: Long,
                end: Long,
            ) = Chapter(index = index, title = "Ch $index", startMs = start, endMs = end)

            test("unknown duration passes chapters through unchanged") {
                val chapters = listOf(ch(1, 0, 1_000), ch(2, 1_000, 5_000))
                clampEmbeddedChapters(chapters, durationMs = null) shouldBe chapters
                clampEmbeddedChapters(chapters, durationMs = 0L) shouldBe chapters
            }

            test("drops a chapter that starts at or past EOF and re-indexes survivors") {
                val chapters = listOf(ch(1, 0, 1_000), ch(2, 2_000, 3_000))
                clampEmbeddedChapters(chapters, durationMs = 1_500L) shouldBe listOf(ch(1, 0, 1_000))
            }

            test("clamps a trailing chapter whose end overruns EOF") {
                val chapters = listOf(ch(1, 0, 2_000))
                clampEmbeddedChapters(chapters, durationMs = 1_500L) shouldBe listOf(ch(1, 0, 1_500))
            }

            test("drops an impossible negative-start chapter and re-indexes the survivor") {
                val chapters = listOf(ch(1, -100, 500), ch(2, 500, 900))
                // The survivor keeps its title but is re-indexed to 1.
                clampEmbeddedChapters(chapters, durationMs = 1_000L) shouldBe
                    listOf(Chapter(index = 1, title = "Ch 2", startMs = 500, endMs = 900))
            }

            test("drops a structurally inverted chapter (end before start) even with no duration bound") {
                val chapters = listOf(ch(1, 0, 1_000), ch(2, 5_000, 2_000))
                clampEmbeddedChapters(chapters, durationMs = null) shouldBe listOf(ch(1, 0, 1_000))
            }
        }
    })
