package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindKey
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private val WEIR = listOf(AudibleContributor("A1", "Andy Weir"))
private val PORTER = listOf(AudibleContributor("", "Ray Porter"))

private fun hit(
    asin: String,
    title: String = "Project Hail Mary",
    minutes: Int = 970,
    format: String = "unabridged",
) = AudibleSearchResult(
    asin = asin,
    title = title,
    subtitle = "",
    authors = WEIR,
    narrators = PORTER,
    coverUrl = "https://img/$asin.jpg",
    runtimeMinutes = minutes,
    releaseDate = "2021-05-04",
    formatType = format,
)

private fun book(asin: String) =
    AudibleBook(
        asin = asin,
        title = "Project Hail Mary",
        subtitle = "",
        authors = WEIR,
        narrators = PORTER,
        publisher = "Audible Studios",
        releaseDate = "2021-05-04",
        runtimeMinutes = 970,
        description = "",
        coverUrl = "https://img/$asin.jpg",
        series = emptyList(),
        genres = emptyList(),
        language = "english",
        rating = 4.8f,
        ratingCount = 10,
        formatType = "unabridged",
    )

/** Records what Audible was asked, store by store. */
private class RecordingAudible(
    val books: Map<Pair<AudibleRegion, String>, AudibleBook> = emptyMap(),
    val searchHits: List<AudibleSearchResult> = emptyList(),
    val searchFailure: MetadataError? = null,
    val chapters: Int = 36,
) : AudibleApi {
    val asked = mutableListOf<String>()

    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> {
        asked += "search:${region.code}:${params.keywords}"
        return searchFailure?.let { AppResult.Failure(it) } ?: AppResult.Success(searchHits)
    }

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> {
        asked += "book:${region.code}:$asin"
        return AppResult.Success(books[region to asin])
    }

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = AppResult.Success(List(chapters) { AudibleChapter("Chapter $it", it * 1_000L, 1_000L) })

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

private class NoITunes : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}

private fun lookup(
    keys: List<FindKey> = emptyList(),
    asin: String? = null,
    identify: Boolean = true,
    text: String = "Project Hail Mary Andy Weir",
) = FindLookup(
    bookId = "book-1",
    identify = identify,
    keys = keys,
    asin = asin,
    isbn = null,
    text = text,
    title = "Project Hail Mary",
    primaryAuthor = "Andy Weir",
)

private fun provider(
    audible: AudibleApi,
    sql: ListenUpDatabase,
): AudibleProvider {
    val clock = FixedClock(Instant.parse("2026-10-05T12:00:00Z"))
    return AudibleProvider(MetadataService(audible, NoITunes(), MetadataCacheRepository(sql, clock), clock = clock))
}

/** Audible in Find (matching redesign PR 2): your link in its own store, the ASIN, then the store asked for. */
class AudibleProviderFindTest :
    FunSpec({
        test("an existing link is resolved in its own store, then the search runs in the store asked for") {
            withSqlDatabase {
                val audible =
                    RecordingAudible(
                        books = mapOf((AudibleRegion.UK to "B0UK") to book("B0UK")),
                        searchHits = listOf(hit("B08G9PRS1K")),
                    )
                runTest {
                    val answer =
                        provider(audible, sql)
                            .findBooks(lookup(keys = listOf(FindKey("B0UK", "uk"))), MetadataLocale("us"))
                            .shouldBeInstanceOf<AppResult.Success<FindAnswer>>()
                            .data

                    audible.asked shouldBe listOf("book:uk:B0UK", "search:us:Project Hail Mary Andy Weir")
                    answer.steps shouldBe setOf(FindStep.LINK, FindStep.TEXT)
                    val linked = answer.books.first()
                    linked.key shouldBe "B0UK"
                    linked.region shouldBe "uk"
                    linked.viaLink shouldBe true
                    linked.chapterCount shouldBe 36
                    linked.narrators shouldBe listOf("Ray Porter")
                    linked.durationMs shouldBe 970 * 60_000L
                    linked.format shouldBe EditionFormat.UNABRIDGED
                    answer.books[1].region shouldBe "us"
                    answer.books[1].viaLink shouldBe false
                }
            }
        }

        test("with no link the book's own ASIN is looked up, and 'Search by title' skips both") {
            withSqlDatabase {
                val audible = RecordingAudible(books = mapOf((AudibleRegion.US to "B1") to book("B1")))
                runTest {
                    val byAsin = provider(audible, sql).findBooks(lookup(asin = "B1"), MetadataLocale("us"))
                    byAsin.shouldBeInstanceOf<AppResult.Success<FindAnswer>>().data.steps shouldBe
                        setOf(FindStep.ASIN, FindStep.TEXT)

                    audible.asked.clear()
                    val byTitle = provider(audible, sql).findBooks(lookup(identify = false), MetadataLocale("us"))
                    byTitle.shouldBeInstanceOf<AppResult.Success<FindAnswer>>().data.steps shouldBe setOf(FindStep.TEXT)
                    // The search itself is served from MetadataService's 24-hour cache; no book was looked up.
                    audible.asked shouldBe emptyList()
                }
            }
        }

        test("a dramatised hit is marked, whatever Audible's format says") {
            withSqlDatabase {
                val audible =
                    RecordingAudible(searchHits = listOf(hit("B2", title = "Project Hail Mary [Dramatized Adaptation]")))
                runTest {
                    val answer = provider(audible, sql).findBooks(lookup(), MetadataLocale("us"))
                    answer
                        .shouldBeInstanceOf<AppResult.Success<FindAnswer>>()
                        .data.books
                        .single()
                        .format shouldBe
                        EditionFormat.DRAMATIZED
                }
            }
        }

        test("a rate-limited search fails the whole answer, with its retry-after") {
            withSqlDatabase {
                val audible = RecordingAudible(searchFailure = MetadataError.ExternalRateLimited(retryAfterSeconds = 12))
                runTest {
                    val failure =
                        provider(audible, sql).findBooks(lookup(), MetadataLocale("us")).shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<MetadataError.ExternalRateLimited>().retryAfterSeconds shouldBe 12L
                }
            }
        }

        test("Audible has a store for every supported region and none for nonsense") {
            withSqlDatabase {
                val audible = provider(RecordingAudible(), sql)
                MetadataLocale.SUPPORTED.all { audible.hasStore(it.region) } shouldBe true
                audible.hasStore("xx") shouldBe false
            }
        }
    })
