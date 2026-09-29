package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.AudibleSeriesEntry
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

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

        // ─── RatingSource ──────────────────────────────────────────────────────────

        test("AudibleBook maps to ExternalRatingMeta when rated") {
            audibleBook().toExternalRatingMeta() shouldBe ExternalRatingMeta(average = 4.8, count = 100, region = "us")
        }

        test("a zero average or zero count maps to null — Audible's unrated-book signal") {
            audibleBook().copy(rating = 0f, ratingCount = 0).toExternalRatingMeta().shouldBeNull()
            audibleBook().copy(rating = 0f, ratingCount = 100).toExternalRatingMeta().shouldBeNull()
            audibleBook().copy(rating = 4.8f, ratingCount = 0).toExternalRatingMeta().shouldBeNull()
        }

        test("getRating returns null without touching the catalog when the book has no ASIN") {
            withSqlDatabase {
                val audible = FakeAudibleApi()
                val provider = AudibleProvider(testMetadataService(audible, sql))
                runTest {
                    val result = provider.getRating(BookIdentity(title = "No Asin"), MetadataLocale.DEFAULT)
                    result.shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data.shouldBeNull()
                    audible.bookCalls shouldBe 0
                }
            }
        }

        test("getRating maps a rated Audible book's average and count") {
            withSqlDatabase {
                val audible = FakeAudibleApi(bookResult = AppResult.Success(audibleBook()))
                val provider = AudibleProvider(testMetadataService(audible, sql))
                runTest {
                    val result =
                        provider.getRating(BookIdentity(asin = "B01", title = "The Way of Kings"), MetadataLocale.DEFAULT)
                    result.shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data shouldBe
                        ExternalRatingMeta(4.8, 100, region = "us")
                }
            }
        }

        test("getRating falls through to the Audible storefront that actually sells a region-locked book") {
            withSqlDatabase {
                val caBook = audibleBook().copy(asin = "B071L4NKN4")
                val audible =
                    object : AudibleApi {
                        override suspend fun search(
                            region: AudibleRegion,
                            params: SearchParams,
                        ): AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList())

                        override suspend fun getBook(
                            region: AudibleRegion,
                            asin: String,
                        ): AppResult<AudibleBook?> = if (region == AudibleRegion.CA) AppResult.Success(caBook) else AppResult.Success(null)

                        override suspend fun getChapters(
                            region: AudibleRegion,
                            asin: String,
                        ): AppResult<List<AudibleChapter>> = AppResult.Success(emptyList())

                        override suspend fun getProductTags(
                            region: AudibleRegion,
                            asin: String,
                        ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
                    }
                val provider = AudibleProvider(testMetadataService(audible, sql))
                runTest {
                    val result =
                        provider.getRating(BookIdentity(asin = "B071L4NKN4", title = "T"), MetadataLocale.DEFAULT)
                    val meta = result.shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data
                    meta.shouldNotBeNull()
                    meta.average shouldBe 4.8
                    meta.count shouldBe 100
                    meta.region shouldBe "ca"
                }
            }
        }

        test("getRating returns null for an unrated Audible book") {
            withSqlDatabase {
                val unrated = audibleBook().copy(rating = 0f, ratingCount = 0)
                val audible = FakeAudibleApi(bookResult = AppResult.Success(unrated))
                val provider = AudibleProvider(testMetadataService(audible, sql))
                runTest {
                    val result = provider.getRating(BookIdentity(asin = "B01", title = "T"), MetadataLocale.DEFAULT)
                    result.shouldBeInstanceOf<AppResult.Success<ExternalRatingMeta?>>().data.shouldBeNull()
                }
            }
        }
    })

// ─── Test helpers ──────────────────────────────────────────────────────────────

/** A [MetadataService] wired to a fake [AudibleApi], with a throwaway iTunes fake and a fixed clock. */
private fun testMetadataService(
    audible: AudibleApi,
    db: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
): MetadataService {
    val clock = FixedClock(Instant.parse("2026-05-24T12:00:00Z"))
    return MetadataService(
        audible = audible,
        itunes = FakeITunesApi(),
        cache = MetadataCacheRepository(db, clock),
        defaultRegion = AudibleRegion.US,
        clock = clock,
    )
}

/** Minimal hand-rolled [AudibleApi] fake — no network, no MockEngine needed at this layer. */
private class FakeAudibleApi(
    private val searchResult: AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList()),
    private val bookResult: AppResult<AudibleBook?> = AppResult.Success(null),
    private val chaptersResult: AppResult<List<AudibleChapter>> = AppResult.Success(emptyList()),
) : AudibleApi {
    var bookCalls = 0
        private set

    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = searchResult

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> {
        bookCalls++
        return bookResult
    }

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = chaptersResult

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

/** Minimal hand-rolled [ITunesApi] fake — [AudibleProvider] never calls it, but [MetadataService] requires one. */
private class FakeITunesApi : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}
