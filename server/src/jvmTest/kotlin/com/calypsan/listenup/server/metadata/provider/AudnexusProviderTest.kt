@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audnexus.AudnexusApi
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthor
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthorProfile
import com.calypsan.listenup.server.metadata.audnexus.AudnexusBook
import com.calypsan.listenup.server.metadata.audnexus.AudnexusChapter
import com.calypsan.listenup.server.metadata.audnexus.AudnexusChapters
import com.calypsan.listenup.server.metadata.audnexus.AudnexusGenre
import com.calypsan.listenup.server.metadata.audnexus.AudnexusNarrator
import com.calypsan.listenup.server.metadata.audnexus.AudnexusSeries
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private val NOW = Instant.parse("2026-05-24T12:00:00Z")
private val US = MetadataLocale("us")

private fun identity(asin: String? = "B01") = BookIdentity(asin = asin, title = "")

class AudnexusProviderTest :
    FunSpec({

        // ── Pure SPI mappers (no HTTP, no DB) ──────────────────────────────────

        test("AudnexusBook maps to BookCoreMeta with credits folded in and no runtime") {
            val core = fullBook().toBookCoreMeta()
            core.title shouldBe "The Way of Kings"
            core.subtitle shouldBe "Book 1"
            core.description shouldBe "Stone and storms."
            core.publisher shouldBe "Macmillan Audio"
            core.releaseDate shouldBe "2010-08-31"
            core.language shouldBe "english"
            core.runtimeMinutes.shouldBeNull()
            core.authors.map { it.name to it.role } shouldBe listOf("Brandon Sanderson" to ContributorRole.AUTHOR)
            core.authors.single().key shouldBe "A1"
            core.narrators.map { it.name to it.role } shouldBe listOf("Kate Reading" to ContributorRole.NARRATOR)
            // Audnexus narrators are name-only — no key.
            core.narrators
                .single()
                .key
                .shouldBeNull()
        }

        test("blank AudnexusBook string fields map to null in BookCoreMeta") {
            val core = fullBook().copy(subtitle = "", description = null, publisherName = "", language = null).toBookCoreMeta()
            core.subtitle.shouldBeNull()
            core.description.shouldBeNull()
            core.publisher.shouldBeNull()
            core.language.shouldBeNull()
        }

        test("Audnexus chapters map to a ChapterListMeta carrying isAccurate and brand offsets") {
            val list =
                AudnexusChapters(
                    brandIntroDurationMs = 2043,
                    brandOutroDurationMs = 0,
                    isAccurate = true,
                    chapters =
                        listOf(
                            AudnexusChapter(title = "Prologue", startOffsetMs = 0, lengthMs = 120000),
                            AudnexusChapter(title = "", startOffsetMs = 120000, lengthMs = 0),
                        ),
                ).toChapterListMeta()
            list.shouldNotBeNull()
            list.accurate shouldBe true
            list.brandIntroMs shouldBe 2043
            // 0 brand outro collapses to null.
            list.brandOutroMs.shouldBeNull()
            list.chapters[0].lengthMs shouldBe 120000
            // Blank title and zero length collapse to null.
            list.chapters[1].title.shouldBeNull()
            list.chapters[1].lengthMs.shouldBeNull()
        }

        test("heuristic (isAccurate=false) chapters map with accurate=false; empty maps to null") {
            AudnexusChapters(isAccurate = false, chapters = listOf(AudnexusChapter(title = "One", startOffsetMs = 0)))
                .toChapterListMeta()!!
                .accurate shouldBe false
            AudnexusChapters(isAccurate = true, chapters = emptyList()).toChapterListMeta().shouldBeNull()
        }

        test("Audnexus image maps to a single CoverMeta keyed by ASIN; absent image maps to empty") {
            fullBook().toCoverMetas() shouldBe listOf(CoverMeta(url = "https://a/cover.jpg", sourceKey = "B01"))
            fullBook().copy(image = null).toCoverMetas().shouldBeEmpty()
        }

        test("primary + secondary series map to SeriesMeta in order, dropping blank names") {
            val series =
                fullBook()
                    .copy(seriesSecondary = AudnexusSeries(asin = "S2", name = "Cosmere", position = "2"))
                    .toSeriesMetas()
            series.map { it.title to it.sequence } shouldBe listOf("The Stormlight Archive" to "1", "Cosmere" to "2")
        }

        test("Audnexus genres route by type: genre → GENRE, tag → TAG, blanks dropped") {
            val genres =
                listOf(
                    AudnexusGenre(name = "Fantasy", type = "genre"),
                    AudnexusGenre(name = "Slow Burn", type = "tag"),
                    AudnexusGenre(name = "", type = "genre"),
                ).toGenreMetas()
            genres.map { it.name to it.kind } shouldBe
                listOf("Fantasy" to GenreKind.GENRE, "Slow Burn" to GenreKind.TAG)
        }

        test("author search hit maps to ContributorHitMeta; an asin-less hit is dropped") {
            AudnexusAuthor(asin = "A1", name = "Pat").toContributorHitMetaOrNull() shouldBe
                ContributorHitMeta(key = "A1", name = "Pat")
            AudnexusAuthor(asin = null, name = "Keyless").toContributorHitMetaOrNull().shouldBeNull()
        }

        test("author profile maps to ContributorMeta (name + bio + photo)") {
            val meta =
                AudnexusAuthorProfile(asin = "A1", name = "Sanderson", description = "bio", image = "https://a/p.jpg")
                    .toContributorMeta()
            meta.key shouldBe "A1"
            meta.name shouldBe "Sanderson"
            meta.description shouldBe "bio"
            meta.imageUrl shouldBe "https://a/p.jpg"
        }

        // ── Capability wiring + caching (fake AudnexusApi + real cache) ────────

        test("getBookCore, getGenres, getSeries, searchCovers all derive from one cached /books fetch") {
            withSqlDatabase {
                val api = CountingAudnexusApi(book = fullBook())
                val provider = provider(api, sql)
                runTest {
                    provider
                        .getBookCore(identity(), US, refresh = false)
                        .shouldBeInstanceOf<AppResult.Success<*>>()
                    provider.getGenres(identity(), US).shouldBeInstanceOf<AppResult.Success<*>>()
                    provider.getSeries(identity(), US).shouldBeInstanceOf<AppResult.Success<*>>()
                    provider.searchCovers(identity(), US).shouldBeInstanceOf<AppResult.Success<*>>()
                    // The book endpoint is hit once — the other three reads come from the cache.
                    api.getBookCalls shouldBe 1
                }
            }
        }

        test("getGenres surfaces the mapped GENRE/TAG split from the fetched book") {
            withSqlDatabase {
                val provider = provider(CountingAudnexusApi(book = fullBook()), sql)
                runTest {
                    val genres =
                        provider.getGenres(identity(), US).shouldBeInstanceOf<AppResult.Success<List<*>?>>().data
                    genres.shouldNotBeNull()
                    genres.map { (it as GenreMeta).kind } shouldBe
                        listOf(GenreKind.GENRE, GenreKind.TAG)
                }
            }
        }

        test("getChapters maps the Audnexus chapters to an accurate ChapterListMeta") {
            withSqlDatabase {
                val api =
                    CountingAudnexusApi(
                        chapters =
                            AudnexusChapters(isAccurate = true, chapters = listOf(AudnexusChapter(title = "Prologue", startOffsetMs = 0))),
                    )
                val provider = provider(api, sql)
                runTest {
                    val chapters =
                        provider.getChapters(identity(), US).shouldBeInstanceOf<AppResult.Success<*>>().data
                    chapters.shouldNotBeNull()
                }
            }
        }

        test("an ASIN-less identity is a Success(null) / empty miss, never a fetch") {
            withSqlDatabase {
                val api = CountingAudnexusApi(book = fullBook())
                val provider = provider(api, sql)
                runTest {
                    provider
                        .getBookCore(identity(asin = null), US, refresh = false)
                        .shouldBeInstanceOf<AppResult.Success<Nothing?>>()
                        .data
                        .shouldBeNull()
                    provider
                        .searchCovers(identity(asin = null), US)
                        .shouldBeInstanceOf<AppResult.Success<List<CoverMeta>>>()
                        .data
                        .shouldBeEmpty()
                    api.getBookCalls shouldBe 0
                }
            }
        }

        test("searchContributors maps hits and drops asin-less ones; getContributor round-trips a profile") {
            withSqlDatabase {
                val api =
                    CountingAudnexusApi(
                        authors = listOf(AudnexusAuthor(asin = "A1", name = "Patrick Rothfuss"), AudnexusAuthor(asin = null, name = "x")),
                        author =
                            AudnexusAuthorProfile(asin = "A1", name = "Patrick Rothfuss", description = "bio", image = "https://a/p.jpg"),
                    )
                val provider = provider(api, sql)
                runTest {
                    val hits =
                        provider
                            .searchContributors("rothfuss", US)
                            .shouldBeInstanceOf<AppResult.Success<List<*>>>()
                            .data
                    hits.map { (it as ContributorHitMeta).key } shouldBe listOf("A1")

                    val profile =
                        provider
                            .getContributor("A1", US, refresh = false)
                            .shouldBeInstanceOf<AppResult.Success<*>>()
                            .data
                    profile.shouldNotBeNull()
                    (profile as ContributorMeta).imageUrl shouldBe "https://a/p.jpg"
                }
            }
        }

        test("id is AUDNEXUS") {
            withSqlDatabase {
                provider(CountingAudnexusApi(), sql).id shouldBe MetadataProviderId.AUDNEXUS
            }
        }
    })

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private fun fullBook() =
    AudnexusBook(
        asin = "B01",
        title = "The Way of Kings",
        subtitle = "Book 1",
        description = "Stone and storms.",
        publisherName = "Macmillan Audio",
        releaseDate = "2010-08-31",
        language = "english",
        image = "https://a/cover.jpg",
        seriesPrimary = AudnexusSeries(asin = "S1", name = "The Stormlight Archive", position = "1"),
        genres = listOf(AudnexusGenre(name = "Fantasy", type = "genre"), AudnexusGenre(name = "Slow Burn", type = "tag")),
        authors = listOf(AudnexusAuthor(asin = "A1", name = "Brandon Sanderson")),
        narrators = listOf(AudnexusNarrator(name = "Kate Reading")),
    )

private fun provider(
    api: AudnexusApi,
    sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
) = AudnexusProvider(client = api, cache = MetadataCacheRepository(sql, clock = FixedClock(NOW)), clock = FixedClock(NOW))

/** A fake [AudnexusApi] with canned responses that counts `getBook` calls (to prove caching dedups). */
private class CountingAudnexusApi(
    private val book: AudnexusBook? = null,
    private val chapters: AudnexusChapters? = null,
    private val authors: List<AudnexusAuthor> = emptyList(),
    private val author: AudnexusAuthorProfile? = null,
) : AudnexusApi {
    var getBookCalls = 0
        private set

    override suspend fun getBook(
        asin: String,
        region: String,
    ): AppResult<AudnexusBook?> {
        getBookCalls++
        return AppResult.Success(book)
    }

    override suspend fun getChapters(
        asin: String,
        region: String,
    ): AppResult<AudnexusChapters?> = AppResult.Success(chapters)

    override suspend fun searchAuthors(
        name: String,
        region: String,
    ): AppResult<List<AudnexusAuthor>> = AppResult.Success(authors)

    override suspend fun getAuthor(
        asin: String,
        region: String,
    ): AppResult<AudnexusAuthorProfile?> = AppResult.Success(author)
}
