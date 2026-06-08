package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.AudibleSeriesEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class AudibleMetadataProviderTest :
    FunSpec({
        test("AudibleSearchResult maps to a sparse MetadataBook") {
            val src =
                AudibleSearchResult(
                    asin = "B01",
                    title = "The Way of Kings",
                    subtitle = "",
                    authors = listOf(AudibleContributor("a1", "Brandon Sanderson")),
                    narrators = listOf(AudibleContributor("", "Kate Reading")),
                    coverUrl = "https://a/c.jpg",
                    runtimeMinutes = 0,
                    releaseDate = "2010-08-31",
                )
            val book = src.toMetadataBook()
            book.asin shouldBe "B01"
            book.title shouldBe "The Way of Kings"
            book.subtitle.shouldBeNull()
            book.description.shouldBeNull()
            book.runtimeMinutes.shouldBeNull() // 0 → null
            book.authors.single().asin shouldBe "a1"
            book.narrators.single().asin.shouldBeNull() // blank → null
            book.series shouldBe emptyList()
            book.genres shouldBe emptyList()
            book.coverUrl shouldBe "https://a/c.jpg"
            book.coverUrlMaxSize.shouldBeNull()
        }

        test("AudibleBook maps to a full MetadataBook including series + genres") {
            val src =
                AudibleBook(
                    asin = "B02",
                    title = "Words of Radiance",
                    subtitle = "Book Two",
                    description = "A long description.",
                    publisher = "Macmillan",
                    releaseDate = "2014-03-04",
                    runtimeMinutes = 1234,
                    language = "english",
                    authors = listOf(AudibleContributor("a1", "Brandon Sanderson")),
                    narrators = listOf(AudibleContributor("n1", "Michael Kramer")),
                    series = listOf(AudibleSeriesEntry(asin = "s1", name = "The Stormlight Archive", position = "2")),
                    genres = listOf("Fantasy"),
                    coverUrl = "https://a/c2.jpg",
                    rating = 4.8f,
                    ratingCount = 1000,
                )
            val book = src.toMetadataBook()
            book.description shouldBe "A long description."
            book.publisher shouldBe "Macmillan"
            book.language shouldBe "english"
            book.runtimeMinutes shouldBe 1234
            book.series.single().title shouldBe "The Stormlight Archive"
            book.series.single().sequence shouldBe "2"
            book.genres shouldBe listOf("Fantasy")
        }

        test("AudibleChapter maps to MetadataChapter") {
            val ch = AudibleChapter(title = "Chapter 1", startMs = 0L, durationMs = 5000L).toMetadataChapter()
            ch.title shouldBe "Chapter 1"
            ch.startMs shouldBe 0L
            ch.lengthMs shouldBe 5000L
        }
    })
