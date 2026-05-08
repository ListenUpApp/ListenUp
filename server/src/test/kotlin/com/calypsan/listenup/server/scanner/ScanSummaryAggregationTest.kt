package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.dto.scanner.UnsupportedFormatCount
import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Pins the [ScanResultSummary] aggregator and human-readable log formatter
 * against deliberate combinations of [MetadataStatus]. The aggregator is
 * the single point where Phase 3 scan summaries are computed; the
 * formatter is the single point where they're rendered for operators.
 */
class ScanSummaryAggregationTest :
    FunSpec({

        test("aggregator counts each MetadataStatus variant correctly") {
            val books =
                listOf(
                    bookWith(status = MetadataStatus.Available, embedded = embeddedMeta(chapters = chapterList(2), withArtwork = true)),
                    bookWith(status = MetadataStatus.Available, embedded = embeddedMeta(chapters = chapterList(0), withArtwork = true)),
                    bookWith(status = MetadataStatus.Available, embedded = embeddedMeta(chapters = chapterList(3), withArtwork = false)),
                    bookWith(status = MetadataStatus.UnsupportedFormat(format = AudioFormat.Flac)),
                    bookWith(status = MetadataStatus.UnsupportedFormat(format = AudioFormat.Flac)),
                    bookWith(status = MetadataStatus.UnsupportedFormat(format = AudioFormat.Opus)),
                    bookWith(status = MetadataStatus.UnsupportedFormat(format = null)),
                    bookWith(status = MetadataStatus.ParseError(corruptHeader())),
                    bookWith(status = null), // candidate with no audio file — excluded from population
                )

            val counters = books.toEmbeddedScanCounters()

            counters.parsed shouldBe 3
            counters.unsupported shouldBe 4
            counters.parseErrors shouldBe 1
            counters.withChapters shouldBe 2
            counters.withArtwork shouldBe 2
            counters.unrecognisedMagic shouldBe 1
            counters.unsupportedFormats shouldContainExactlyInAnyOrder
                listOf(
                    UnsupportedFormatCount(format = AudioFormat.Flac, count = 2),
                    UnsupportedFormatCount(format = AudioFormat.Opus, count = 1),
                )
        }

        test("aggregator returns zeroed counters when no books are present") {
            val counters = emptyList<AnalyzedBook>().toEmbeddedScanCounters()
            counters.parsed shouldBe 0
            counters.unsupported shouldBe 0
            counters.parseErrors shouldBe 0
            counters.unsupportedFormats shouldBe emptyList()
        }

        test("log formatter omits embedded section when no books were embedded-eligible") {
            val summary =
                ScanResultSummary(
                    correlationId = "c",
                    totalBooks = 5,
                    added = 5,
                    modified = 0,
                    removed = 0,
                    moved = 0,
                    errors = 0,
                    durationMs = 200,
                    filesWalked = 10,
                )

            formatScanCompleteLog(summary) shouldBe "5 books, 5 changes, 0 errors in 200ms"
        }

        test("log formatter renders embedded counters and per-format breakdown") {
            val summary =
                ScanResultSummary(
                    correlationId = "c",
                    totalBooks = 9,
                    added = 9,
                    modified = 0,
                    removed = 0,
                    moved = 0,
                    errors = 0,
                    durationMs = 350,
                    filesWalked = 30,
                    embedded =
                        listOf(
                            bookWith(MetadataStatus.Available, embeddedMeta(chapterList(2), withArtwork = true)),
                            bookWith(MetadataStatus.Available, embeddedMeta(chapterList(0), withArtwork = true)),
                            bookWith(MetadataStatus.Available, embeddedMeta(chapterList(3), withArtwork = false)),
                            bookWith(MetadataStatus.UnsupportedFormat(AudioFormat.Flac)),
                            bookWith(MetadataStatus.UnsupportedFormat(AudioFormat.Flac)),
                            bookWith(MetadataStatus.UnsupportedFormat(AudioFormat.Opus)),
                            bookWith(MetadataStatus.UnsupportedFormat(format = null)),
                            bookWith(MetadataStatus.ParseError(corruptHeader())),
                        ).toEmbeddedScanCounters(),
                )

            val rendered = formatScanCompleteLog(summary)
            rendered shouldBe
                "9 books, 9 changes, 0 errors in 350ms | embedded: 3 parsed (2 w/chapters, 2 w/artwork), 4 unsupported [Flac=2,Opus=1], 1 unrecognised, 1 parse errors"
        }
    })

private fun bookWith(
    status: MetadataStatus?,
    embedded: EmbeddedAudioMetadata? = null,
): AnalyzedBook =
    AnalyzedBook(
        candidate = CandidateBook(rootRelPath = "Author/Title", isFile = false, files = emptyList()),
        title = "Title",
        embedded = embedded,
        embeddedStatus = status,
    )

private fun embeddedMeta(
    chapters: List<Chapter>,
    withArtwork: Boolean,
): EmbeddedAudioMetadata =
    EmbeddedAudioMetadata(
        format = AudioFormat.Mp3,
        durationMs = 60_000,
        tags = emptyTags(),
        chapters = chapters,
        chaptersSource = if (chapters.isEmpty()) ChapterSource.None else ChapterSource.Id3v2Chap,
        artwork = if (withArtwork) EmbeddedArtwork(mime = "image/jpeg", bytes = byteArrayOf(0xFF.toByte())) else null,
    )

private fun emptyTags(): AudioTags =
    AudioTags(
        title = null,
        subtitle = null,
        authors = emptyList(),
        narrators = emptyList(),
        series = emptyList(),
        genres = emptyList(),
        description = null,
        publisher = null,
        publishedYear = null,
        asin = null,
        isbn = null,
        language = null,
        trackNumber = null,
        discNumber = null,
        custom = emptyMap(),
    )

private fun chapterList(count: Int): List<Chapter> =
    (0 until count).map { i ->
        Chapter(index = i + 1, title = "Chapter ${i + 1}", startMs = i * 30_000L, endMs = (i + 1) * 30_000L)
    }

private fun corruptHeader(): AudioMetadataError =
    AudioMetadataError.CorruptHeader(
        pathString = "/lib/bad.mp3",
        format = AudioFormat.Mp3,
        offset = 42,
        expected = "ID3",
    )
