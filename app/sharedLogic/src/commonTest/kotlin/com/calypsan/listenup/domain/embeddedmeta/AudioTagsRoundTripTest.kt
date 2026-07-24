package com.calypsan.listenup.domain.embeddedmeta

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class AudioTagsRoundTripTest :
    FunSpec({
        val json = Json {}

        test("AudioTags with all fields round-trips") {
            val tags =
                AudioTags(
                    title = "The Way of Kings",
                    subtitle = "Book One of the Stormlight Archive",
                    authors = listOf("Brandon Sanderson"),
                    narrators = listOf("Michael Kramer", "Kate Reading"),
                    series = listOf(SeriesEntry("The Stormlight Archive", "1")),
                    genres = listOf("Fantasy"),
                    description = "A long book.",
                    publisher = "Tor",
                    publishedYear = 2010,
                    asin = "B003P2WO5E",
                    isbn = "9780765326355",
                    language = "en",
                    trackNumber = 1,
                    discNumber = 1,
                    custom = mapOf("MOOD" to "Epic"),
                )
            val encoded = json.encodeToString(AudioTags.serializer(), tags)
            val decoded = json.decodeFromString(AudioTags.serializer(), encoded)
            decoded shouldBe tags
        }

        test("Chapter round-trips") {
            val chapter = Chapter(index = 1, title = "Prologue", startMs = 0, endMs = 12_345)
            val decoded =
                json.decodeFromString(
                    Chapter.serializer(),
                    json.encodeToString(Chapter.serializer(), chapter),
                )
            decoded shouldBe chapter
        }

        test("ChapterSource subtypes round-trip") {
            val sources: List<ChapterSource> =
                listOf(
                    ChapterSource.Mp4Chpl,
                    ChapterSource.Mp4TextTrack,
                    ChapterSource.Id3v2Chap,
                    ChapterSource.FlacCuesheet,
                    ChapterSource.OggVorbisComment,
                    ChapterSource.None,
                )
            sources.forEach { source ->
                val decoded =
                    json.decodeFromString(
                        ChapterSource.serializer(),
                        json.encodeToString(ChapterSource.serializer(), source),
                    )
                decoded shouldBe source
            }
        }
    })
