package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.metadata.BookField
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class MatchProvenanceRoundTripTest :
    FunSpec({
        test("MetadataBook with matchProvenance survives a JSON round-trip") {
            val book =
                sampleBook().copy(
                    matchProvenance =
                        MatchProvenance(
                            contributingSources = listOf("Audible", "Audnexus", "iTunes"),
                            fallbackFields = mapOf(BookField.DESCRIPTION to "Audnexus"),
                            coverSource = "iTunes",
                            coverWidth = 3000,
                            coverHeight = 3000,
                        ),
                )
            val decoded = contractJson.decodeFromString<MetadataBook>(contractJson.encodeToString(book))
            decoded shouldBe book
        }

        test("legacy MetadataBook JSON without matchProvenance decodes to null") {
            val legacy = contractJson.encodeToString(sampleBook())
            contractJson.decodeFromString<MetadataBook>(legacy).matchProvenance shouldBe null
        }
    })

private fun sampleBook() =
    MetadataBook(
        asin = "B001",
        title = "The Way of Kings",
        subtitle = null,
        description = "d",
        publisher = null,
        releaseDate = null,
        runtimeMinutes = null,
        language = null,
        authors = emptyList(),
        narrators = emptyList(),
        series = emptyList(),
        genres = emptyList(),
        coverUrl = null,
        coverUrlMaxSize = null,
    )
