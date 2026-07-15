package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class OverdriveChaptersTest :
    FunSpec({

        test("single file: markers become chapters with cumulative-from-zero start times") {
            val track = trackEntry("Book.mp3")
            val meta =
                overdriveTrack(
                    durationMs = 1_800_000L,
                    markers = listOf("Chapter 1" to "0:00.000", "Chapter 2" to "15:51.000"),
                )

            val chapters = OverdriveChapters.parse(listOf(track)) { meta }

            chapters.shouldNotBeNullAnd {
                it shouldHaveSize 2
                it[0].index shouldBe 1
                it[0].title shouldBe "Chapter 1"
                it[0].startMs shouldBe 0L
                it[0].endMs shouldBe 951_000L
                it[1].index shouldBe 2
                it[1].title shouldBe "Chapter 2"
                it[1].startMs shouldBe 951_000L
                it[1].endMs shouldBe 1_800_000L // last chapter runs to the book's end
            }
        }

        test("multi file: per-track times are offset by preceding track durations") {
            val tracks = listOf(trackEntry("01.mp3"), trackEntry("02.mp3"))
            val byTrack =
                mapOf(
                    tracks[0] to overdriveTrack(60_000L, listOf("Chapter 1" to "0:00.000")),
                    tracks[1] to overdriveTrack(120_000L, listOf("Chapter 2" to "0:00.000")),
                )

            val chapters = OverdriveChapters.parse(tracks) { byTrack[it] }

            chapters.shouldNotBeNullAnd {
                it shouldHaveSize 2
                it[0].startMs shouldBe 0L
                it[0].endMs shouldBe 60_000L
                it[1].startMs shouldBe 60_000L // offset by track 1's duration
                it[1].endMs shouldBe 180_000L
            }
        }

        test("all-or-nothing: a track with no marker frame aborts to null") {
            val tracks = listOf(trackEntry("01.mp3"), trackEntry("02.mp3"))
            val byTrack =
                mapOf(
                    tracks[0] to overdriveTrack(60_000L, listOf("Chapter 1" to "0:00.000")),
                    tracks[1] to embeddedNoMarkers(120_000L), // no OverDrive frame
                )

            OverdriveChapters.parse(tracks) { byTrack[it] }.shouldBeNull()
        }

        test("null metadata for any track aborts to null") {
            val tracks = listOf(trackEntry("01.mp3"), trackEntry("02.mp3"))
            val byTrack = mapOf(tracks[0] to overdriveTrack(60_000L, listOf("Chapter 1" to "0:00.000")))

            OverdriveChapters.parse(tracks) { byTrack[it] }.shouldBeNull()
        }

        test("weird sub-chapters — (N and 'continued' — are filtered") {
            val track = trackEntry("Book.mp3")
            val meta =
                overdriveTrack(
                    durationMs = 60_000L,
                    markers =
                        listOf(
                            "Chapter 1" to "0:00.000",
                            "Chapter 1 (02)" to "0:20.000",
                            "Chapter 1 continued" to "0:40.000",
                        ),
                )

            val chapters = OverdriveChapters.parse(listOf(track)) { meta }

            chapters.shouldNotBeNullAnd {
                it shouldHaveSize 1
                it[0].title shouldBe "Chapter 1"
                it[0].endMs shouldBe 60_000L
            }
        }

        test("time parses hours, minutes, seconds, and millis") {
            val track = trackEntry("Book.mp3")
            val meta = overdriveTrack(10_000_000L, listOf("Deep" to "1:02:03.500"))

            OverdriveChapters.parse(listOf(track)) { meta }.shouldNotBeNullAnd {
                it[0].startMs shouldBe 3_723_500L // 1h + 2m + 3.5s
            }
        }

        test("unparseable marker XML aborts to null") {
            val track = trackEntry("Book.mp3")
            val meta = overdriveTrack(60_000L, rawMarkerXml = "<not-closed")

            OverdriveChapters.parse(listOf(track)) { meta }.shouldBeNull()
        }

        test("a marker block whose chapters are all filtered contributes nothing → null") {
            val track = trackEntry("Book.mp3")
            val meta = overdriveTrack(60_000L, listOf("Section (1)" to "0:00.000"))

            OverdriveChapters.parse(listOf(track)) { meta }.shouldBeNull()
        }

        test("near-duplicate sub-0.1s markers are dropped as ghosts") {
            val track = trackEntry("Book.mp3")
            val meta =
                overdriveTrack(
                    durationMs = 600_000L,
                    markers =
                        listOf(
                            "Chapter 1" to "0:00.000",
                            "Chapter 1 alt" to "0:00.050", // 50ms after → first chapter is a ghost
                            "Chapter 2" to "0:10.000",
                        ),
                )

            OverdriveChapters.parse(listOf(track)) { meta }.shouldNotBeNullAnd {
                it.map { c -> c.title } shouldBe listOf("Chapter 1 alt", "Chapter 2")
                it.map { c -> c.index } shouldBe listOf(1, 2)
            }
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

/** An [EmbeddedAudioMetadata] carrying an OverDrive marker frame built from `(name, time)` pairs. */
private fun overdriveTrack(
    durationMs: Long,
    markers: List<Pair<String, String>> = emptyList(),
    rawMarkerXml: String? = null,
): EmbeddedAudioMetadata {
    val xml =
        rawMarkerXml ?: buildString {
            append("<Markers>")
            markers.forEach { (name, time) -> append("<Marker><Name>$name</Name><Time>$time</Time></Marker>") }
            append("</Markers>")
        }
    return embeddedNoMarkers(durationMs).let {
        it.copy(tags = it.tags.copy(custom = mapOf(OverdriveChapters.MARKERS_TAG_KEY to xml)))
    }
}

private fun embeddedNoMarkers(durationMs: Long): EmbeddedAudioMetadata =
    EmbeddedAudioMetadata(
        format = AudioFormat.Mp3,
        durationMs = durationMs,
        tags =
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
            ),
        chapters = emptyList(),
        chaptersSource = ChapterSource.None,
        artwork = null,
    )

private inline fun <T> T?.shouldNotBeNullAnd(block: (T) -> Unit) {
    block(checkNotNull(this) { "expected non-null chapters" })
}
