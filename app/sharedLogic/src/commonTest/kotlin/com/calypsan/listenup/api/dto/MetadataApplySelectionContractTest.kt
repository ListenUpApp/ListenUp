package com.calypsan.listenup.api.dto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class MetadataApplySelectionContractTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("MetadataApplySelection round-trips with a chosen coverUrl") {
            val selection =
                MetadataApplySelection(
                    title = true,
                    subtitle = false,
                    description = true,
                    publisher = false,
                    releaseDate = true,
                    language = false,
                    cover = true,
                    authorAsins = setOf("B01AUTHOR"),
                    narratorAsins = emptySet(),
                    seriesAsins = emptySet(),
                    coverUrl = "https://itunes/7000x7000.jpg",
                )
            json.decodeFromString(MetadataApplySelection.serializer(), json.encodeToString(MetadataApplySelection.serializer(), selection)) shouldBe selection
        }

        test("MetadataApplySelection round-trips with selected genres") {
            val selection =
                MetadataApplySelection(
                    title = true,
                    subtitle = false,
                    description = true,
                    publisher = false,
                    releaseDate = true,
                    language = false,
                    cover = true,
                    authorAsins = setOf("B01AUTHOR"),
                    narratorAsins = emptySet(),
                    seriesAsins = emptySet(),
                    coverUrl = null,
                    genres = setOf("Fantasy", "Science Fiction & Fantasy"),
                )
            json.decodeFromString(MetadataApplySelection.serializer(), json.encodeToString(MetadataApplySelection.serializer(), selection)) shouldBe selection
        }

        test("MetadataApplySelection round-trips with selected moods and tags") {
            val selection =
                MetadataApplySelection(
                    title = true,
                    subtitle = false,
                    description = true,
                    publisher = false,
                    releaseDate = true,
                    language = false,
                    cover = true,
                    authorAsins = setOf("B01AUTHOR"),
                    narratorAsins = emptySet(),
                    seriesAsins = emptySet(),
                    coverUrl = null,
                    genres = setOf("Fantasy"),
                    moods = setOf("Dark", "Tense"),
                    tags = setOf("Found Family", "Slow Burn"),
                )
            json.decodeFromString(MetadataApplySelection.serializer(), json.encodeToString(MetadataApplySelection.serializer(), selection)) shouldBe selection
        }

        test("MetadataApplySelection survives a JSON round-trip") {
            val selection =
                MetadataApplySelection(
                    title = true,
                    subtitle = false,
                    description = true,
                    publisher = false,
                    releaseDate = true,
                    language = false,
                    cover = true,
                    authorAsins = setOf("B01AUTHOR"),
                    narratorAsins = emptySet(),
                    seriesAsins = setOf("B01SERIES", "B02SERIES"),
                )
            val decoded =
                json.decodeFromString(
                    MetadataApplySelection.serializer(),
                    json.encodeToString(MetadataApplySelection.serializer(), selection),
                )
            decoded shouldBe selection
        }
    })
