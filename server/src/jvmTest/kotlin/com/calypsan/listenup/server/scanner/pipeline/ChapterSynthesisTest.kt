package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class ChapterSynthesisTest :
    FunSpec({

        test("pickChapterTitle: track TIT2 different from book title wins") {
            val track = trackEntry("01.mp3")
            val trackMeta = embeddedTagsTitle("Foreword")
            pickChapterTitle(track, bookTitle = "The Way of Kings", trackMeta = trackMeta, trackIndex = 1) shouldBe
                "Foreword"
        }

        test("pickChapterTitle: track TIT2 equal to book title falls through to filename") {
            val track = trackEntry("01 The Way of Kings - Part 1.mp3")
            val trackMeta = embeddedTagsTitle("The Way of Kings")
            pickChapterTitle(track, bookTitle = "The Way of Kings", trackMeta = trackMeta, trackIndex = 1) shouldBe
                "The Way of Kings - Part 1"
        }

        test("pickChapterTitle: blank track TIT2 falls through to filename") {
            val track = trackEntry("01-01 The Girl Who Kicked the Hornet's Nest.mp3")
            val trackMeta = embeddedTagsTitle(title = "")
            pickChapterTitle(
                track,
                bookTitle = "The Girl Who Played With Fire",
                trackMeta = trackMeta,
                trackIndex = 1,
            ) shouldBe "The Girl Who Kicked the Hornet's Nest"
        }

        test("pickChapterTitle: null trackMeta uses filename") {
            val track = trackEntry("Chapter_3_Chapter_4_96Kbps.mp3")
            pickChapterTitle(
                track,
                bookTitle = "Some Book",
                trackMeta = null,
                trackIndex = 7,
            ) shouldBe "Chapter 3 Chapter 4 96Kbps"
        }

        test("pickChapterTitle: filename equals book title falls through to Track N") {
            val track = trackEntry("Some Book.mp3")
            pickChapterTitle(
                track,
                bookTitle = "Some Book",
                trackMeta = null,
                trackIndex = 5,
            ) shouldBe "Track 5"
        }

        test("pickChapterTitle: empty cleaned filename falls through to Track N") {
            // Filename is just a track-number prefix that the regex strips entirely.
            val track = trackEntry("01.mp3")
            pickChapterTitle(
                track,
                bookTitle = "Some Book",
                trackMeta = null,
                trackIndex = 1,
            ) shouldBe "Track 1"
        }

        test("cleanFilename: strips Disc/CD/Track word prefix") {
            cleanFilename("Disc 1 Track 02_Foreword.mp3") shouldBe "Foreword"
            cleanFilename("CD02-04 Chapter Two.mp3") shouldBe "Chapter Two"
            cleanFilename("Track 01 - Prologue.mp3") shouldBe "Prologue"
        }

        test("synthesizeChapters: cumulative startMs/endMs follow track durations") {
            val tracks =
                listOf(
                    trackEntry("01 Foreword.mp3"),
                    trackEntry("02 Prologue.mp3"),
                    trackEntry("03 Chapter One.mp3"),
                )
            val perTrackMetadata =
                mapOf(
                    tracks[0] to embeddedDuration(60_000L),
                    tracks[1] to embeddedDuration(120_000L),
                    tracks[2] to embeddedDuration(180_000L),
                )

            val chapters =
                synthesizeChapters(
                    tracks = tracks,
                    perTrackMetadata = perTrackMetadata,
                    bookTitle = "Some Book",
                )

            chapters shouldHaveSize 3
            chapters[0].index shouldBe 1
            chapters[0].title shouldBe "Foreword"
            chapters[0].startMs shouldBe 0L
            chapters[0].endMs shouldBe 60_000L
            chapters[1].index shouldBe 2
            chapters[1].title shouldBe "Prologue"
            chapters[1].startMs shouldBe 60_000L
            chapters[1].endMs shouldBe 180_000L
            chapters[2].index shouldBe 3
            chapters[2].title shouldBe "Chapter One"
            chapters[2].startMs shouldBe 180_000L
            chapters[2].endMs shouldBe 360_000L
        }

        test("synthesizeChapters: parse-failed track's zero-length ghost chapter is dropped") {
            val tracks =
                listOf(
                    trackEntry("01 First.mp3"),
                    trackEntry("02 Failed.mp3"),
                    trackEntry("03 Third.mp3"),
                )
            val perTrackMetadata =
                mapOf(
                    tracks[0] to embeddedDuration(60_000L),
                    tracks[1] to null, // parse failed for this track
                    tracks[2] to embeddedDuration(90_000L),
                )

            val chapters =
                synthesizeChapters(
                    tracks = tracks,
                    perTrackMetadata = perTrackMetadata,
                    bookTitle = "Some Book",
                )

            // The zero-length "Failed" ghost is dropped and survivors re-number 1-based.
            chapters shouldHaveSize 2
            chapters[0].index shouldBe 1
            chapters[0].title shouldBe "First"
            chapters[0].startMs shouldBe 0L
            chapters[0].endMs shouldBe 60_000L
            // Track 3 still picks up where track 1 left off (track 2 contributed 0ms).
            chapters[1].index shouldBe 2
            chapters[1].title shouldBe "Third"
            chapters[1].startMs shouldBe 60_000L
            chapters[1].endMs shouldBe 150_000L
        }

        test("dropGhostChapters: drops sub-0.1s ghosts, re-numbers, and keeps boundaries gap-free") {
            val chapters =
                listOf(
                    Chapter(index = 1, title = "Real", startMs = 0L, endMs = 100L),
                    Chapter(index = 2, title = "Ghost", startMs = 100L, endMs = 199L), // 99ms, dropped
                    Chapter(index = 3, title = "Also Real", startMs = 199L, endMs = 5_000L),
                )

            val kept = chapters.dropGhostChapters()

            kept.map { it.title } shouldBe listOf("Real", "Also Real")
            kept.map { it.index } shouldBe listOf(1, 2)
            // The previous survivor absorbs the dropped ghost's span — no coverage hole.
            kept[0].endMs shouldBe kept[1].startMs
            kept[0].endMs shouldBe 199L
            kept[1].endMs shouldBe 5_000L // final survivor keeps its own end
        }

        test("dropGhostChapters: a leading ghost is absorbed so the first survivor still starts at 0") {
            val chapters =
                listOf(
                    Chapter(index = 1, title = "Intro blip", startMs = 0L, endMs = 40L), // 40ms, dropped
                    Chapter(index = 2, title = "Real", startMs = 40L, endMs = 5_000L),
                )

            val kept = chapters.dropGhostChapters()

            kept shouldHaveSize 1
            kept[0].title shouldBe "Real"
            kept[0].startMs shouldBe 0L // pulled back to the original head — no uncovered start
            kept[0].endMs shouldBe 5_000L
        }
    })

private fun trackEntry(filename: String): TrackEntry =
    TrackEntry(
        file =
            FileEntry(
                relPath = "Author/Title/$filename",
                name = filename,
                ext = filename.substringAfterLast('.', "").lowercase(),
                size = 1024,
                mtimeMs = 0,
                inode = null,
                fileType = FileType.AUDIO,
            ),
    )

private fun embeddedTagsTitle(title: String?): EmbeddedAudioMetadata =
    EmbeddedAudioMetadata(
        format = AudioFormat.Mp3,
        durationMs = 1_000L,
        tags =
            AudioTags(
                title = title,
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
            ),
        chapters = emptyList(),
        chaptersSource = ChapterSource.None,
        artwork = null,
    )

private fun embeddedDuration(durationMs: Long): EmbeddedAudioMetadata = embeddedTagsTitle(title = null).copy(durationMs = durationMs)
