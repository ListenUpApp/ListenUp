package com.calypsan.listenup.domain.embeddedmeta

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class EmbeddedAudioMetadataTest :
    FunSpec({
        val json = Json {}

        test("full-shape EmbeddedAudioMetadata round-trips") {
            val meta =
                EmbeddedAudioMetadata(
                    format = AudioFormat.Mp4,
                    durationMs = 36_000_000L,
                    tags =
                        AudioTags(
                            title = "The Way of Kings",
                            subtitle = null,
                            authors = listOf("Brandon Sanderson"),
                            narrators = listOf("Michael Kramer"),
                            series = listOf(SeriesEntry("Stormlight Archive", "1")),
                            genres = listOf("Fantasy"),
                            description = null,
                            publisher = null,
                            publishedYear = 2010,
                            asin = null,
                            isbn = null,
                            language = "en",
                            trackNumber = null,
                            discNumber = null,
                            custom = emptyMap(),
                        ),
                    chapters =
                        listOf(
                            Chapter(index = 1, title = "Prologue", startMs = 0, endMs = 600_000),
                            Chapter(index = 2, title = "Chapter 1", startMs = 600_000, endMs = 1_800_000),
                        ),
                    chaptersSource = ChapterSource.Mp4Chpl,
                    artwork = EmbeddedArtwork("image/jpeg", byteArrayOf(1, 2, 3)),
                )
            val decoded =
                json.decodeFromString(
                    EmbeddedAudioMetadata.serializer(),
                    json.encodeToString(EmbeddedAudioMetadata.serializer(), meta),
                )
            decoded shouldBe meta
        }
    })
