package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.AudibleSeriesEntry
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.GenreKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Covers the Audible → neutral-SPI mappers that back [AudibleProvider]. The provider methods
 * are one-line `.map { it.toX() }` delegations over `MetadataService`, so the mapping logic —
 * where the substance lives — is exercised here as pure functions.
 */
class AudibleProviderTest :
    FunSpec({
        fun audibleBook() =
            AudibleBook(
                asin = "B01",
                title = "The Way of Kings",
                subtitle = "The Stormlight Archive, Book 1",
                authors = listOf(AudibleContributor("a1", "Brandon Sanderson")),
                narrators = listOf(AudibleContributor("", "Kate Reading"), AudibleContributor("n2", "Michael Kramer")),
                publisher = "Macmillan Audio",
                releaseDate = "2010-08-31",
                runtimeMinutes = 2734,
                description = "Roshar is a world of stone and storms.",
                coverUrl = "https://a/wok.jpg",
                series = listOf(AudibleSeriesEntry(asin = "S1", name = "The Stormlight Archive", position = "1")),
                genres = listOf("Fantasy", "Epic"),
                language = "english",
                rating = 4.8f,
                ratingCount = 100,
            )

        test("AudibleSearchResult maps to a ranked BookMatch with runtime in ms") {
            val match =
                AudibleSearchResult(
                    asin = "B01",
                    title = "The Way of Kings",
                    subtitle = "",
                    authors = listOf(AudibleContributor("a1", "Brandon Sanderson")),
                    narrators = emptyList(),
                    coverUrl = "https://a/c.jpg",
                    runtimeMinutes = 60,
                    releaseDate = "2010-08-31",
                ).toBookMatch()

            match.asin shouldBe "B01"
            match.title shouldBe "The Way of Kings"
            match.author shouldBe "Brandon Sanderson"
            match.durationMs shouldBe 60 * 60_000L
            match.coverUrl shouldBe "https://a/c.jpg"
            match.score shouldBe 1.0
        }

        test("zero runtime and blank cover map to null in BookMatch") {
            val match =
                AudibleSearchResult(
                    asin = "B02",
                    title = "T",
                    subtitle = "",
                    authors = emptyList(),
                    narrators = emptyList(),
                    coverUrl = "",
                    runtimeMinutes = 0,
                    releaseDate = "",
                ).toBookMatch()

            match.durationMs.shouldBeNull()
            match.coverUrl.shouldBeNull()
            match.author.shouldBeNull()
        }

        test("AudibleBook maps to BookCoreMeta with credits folded into authors/narrators") {
            val core = audibleBook().toBookCoreMeta()

            core.title shouldBe "The Way of Kings"
            core.subtitle shouldBe "The Stormlight Archive, Book 1"
            core.description shouldBe "Roshar is a world of stone and storms."
            core.publisher shouldBe "Macmillan Audio"
            core.releaseDate shouldBe "2010-08-31"
            core.language shouldBe "english"
            core.explicit.shouldBeNull()
            core.abridged.shouldBeNull()

            core.authors.map { it.name to it.role } shouldBe listOf("Brandon Sanderson" to ContributorRole.AUTHOR)
            core.authors.single().key shouldBe "a1"
            core.narrators.map { it.name to it.role } shouldBe
                listOf("Kate Reading" to ContributorRole.NARRATOR, "Michael Kramer" to ContributorRole.NARRATOR)
            // Blank contributor ASIN becomes a null key.
            core.narrators
                .first()
                .key
                .shouldBeNull()
        }

        test("blank AudibleBook string fields map to null in BookCoreMeta") {
            val core =
                audibleBook()
                    .copy(subtitle = "", description = "", publisher = "", releaseDate = "", language = "")
                    .toBookCoreMeta()

            core.subtitle.shouldBeNull()
            core.description.shouldBeNull()
            core.publisher.shouldBeNull()
            core.releaseDate.shouldBeNull()
            core.language.shouldBeNull()
        }

        test("Audible chapters map to an accurate ChapterListMeta") {
            val list =
                listOf(
                    AudibleChapter(title = "Prologue", startMs = 0, durationMs = 120_000),
                    AudibleChapter(title = "", startMs = 120_000, durationMs = 0),
                ).toChapterListMeta()

            list.shouldNotBeNull()
            list.accurate shouldBe true
            list.chapters[0].title shouldBe "Prologue"
            list.chapters[0].startMs shouldBe 0
            list.chapters[0].lengthMs shouldBe 120_000
            // Blank title and zero length collapse to null.
            list.chapters[1].title.shouldBeNull()
            list.chapters[1].lengthMs.shouldBeNull()
        }

        test("empty chapter list maps to null (catalog miss)") {
            emptyList<AudibleChapter>().toChapterListMeta().shouldBeNull()
        }

        test("Audible series maps to SeriesMeta with verbatim sequence") {
            val series = AudibleSeriesEntry(asin = "S1", name = "Stormlight", position = "1.5").toSeriesMeta()
            series.key shouldBe "S1"
            series.title shouldBe "Stormlight"
            series.sequence shouldBe "1.5"
        }

        test("Audible genres map to GENRE-kind terms, dropping blanks") {
            val genres = listOf("Fantasy", "", "Epic").toGenreMetas()
            genres.map { it.name } shouldBe listOf("Fantasy", "Epic")
            genres.all { it.kind == GenreKind.GENRE } shouldBe true
        }

        test("Audible cover search selects the first result with a non-blank cover") {
            fun hit(
                asin: String,
                cover: String,
            ) = AudibleSearchResult(
                asin = asin,
                title = "T",
                subtitle = "",
                authors = emptyList(),
                narrators = emptyList(),
                coverUrl = cover,
                runtimeMinutes = 0,
                releaseDate = "",
            )

            listOf(hit("B1", ""), hit("B2", "https://a/cover.jpg")).toCoverMetas() shouldBe
                listOf(CoverMeta(url = "https://a/cover.jpg", sourceKey = "B2"))
            listOf(hit("B1", "")).toCoverMetas() shouldBe emptyList()
        }
    })
