@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class MetadataServiceTest :
    FunSpec({
        val now = Instant.parse("2026-05-24T12:00:00Z")

        // ── search ─────────────────────────────────────────────────────────────

        test("search caches the response on first call and serves from cache on second") {
            withSqlDatabase {
                val audible = FakeAudibleApi(searchResult = AppResult.Success(listOf(searchResult("B001"))))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    val first = service.search(AudibleRegion.US, SearchParams(keywords = "sanderson"))
                    val second = service.search(AudibleRegion.US, SearchParams(keywords = "sanderson"))
                    first shouldBe second
                    audible.searchCalls shouldBe 1 // second call served from cache
                }
            }
        }

        test("search with refresh = true bypasses the cache") {
            withSqlDatabase {
                val audible = FakeAudibleApi(searchResult = AppResult.Success(listOf(searchResult("B001"))))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    service.search(AudibleRegion.US, SearchParams(keywords = "sanderson"))
                    service.search(AudibleRegion.US, SearchParams(keywords = "sanderson"), refresh = true)
                    audible.searchCalls shouldBe 2
                }
            }
        }

        test("search without keywords skips the cache") {
            withSqlDatabase {
                val audible = FakeAudibleApi(searchResult = AppResult.Success(emptyList()))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    service.search(AudibleRegion.US, SearchParams(title = "Dune", keywords = null))
                    service.search(AudibleRegion.US, SearchParams(title = "Dune", keywords = null))
                    audible.searchCalls shouldBe 2 // no keywords → both calls hit Audible
                }
            }
        }

        test("search returns AppResult.Failure when AudibleApi returns failure") {
            withSqlDatabase {
                val audible =
                    FakeAudibleApi(
                        searchResult = AppResult.Failure(MetadataError.ExternalUnavailable()),
                    )
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    val result = service.search(AudibleRegion.US, SearchParams(keywords = "sanderson"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        // ── searchWithFallback ─────────────────────────────────────────────────

        test("searchWithFallback returns results from default region when non-empty") {
            withSqlDatabase {
                val audible = FakeAudibleApi(searchResult = AppResult.Success(listOf(searchResult("B001"))))
                val service = makeService(audible = audible, db = sql, defaultRegion = AudibleRegion.UK, clock = FixedClock(now))
                runTest {
                    val result = service.searchWithFallback(SearchParams(keywords = "tolkien"))
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    audible.searchCalls shouldBe 1 // only tried UK
                }
            }
        }

        test("searchWithFallback falls back to US when default region returns empty") {
            withSqlDatabase {
                var callCount = 0
                val audible =
                    object : FakeAudibleApi(searchResult = AppResult.Success(emptyList())) {
                        override suspend fun search(
                            region: AudibleRegion,
                            params: SearchParams,
                        ): AppResult<List<AudibleSearchResult>> {
                            callCount++
                            // Return non-empty only when the fallback (US) is used.
                            return if (region == AudibleRegion.US) {
                                AppResult.Success(listOf(searchResult("B002")))
                            } else {
                                AppResult.Success(emptyList())
                            }
                        }
                    }
                val service = makeService(audible = audible, db = sql, defaultRegion = AudibleRegion.UK, clock = FixedClock(now))
                runTest {
                    val result = service.searchWithFallback(SearchParams(keywords = "tolkien"))
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    (result as AppResult.Success).data.size shouldBe 1
                    callCount shouldBe 2 // tried UK, then US
                }
            }
        }

        // ── getBook ────────────────────────────────────────────────────────────

        test("getBook caches the result; second call does not hit AudibleApi") {
            withSqlDatabase {
                val audible = FakeAudibleApi(bookResult = AppResult.Success(book("B001")))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    service.getBook(AudibleRegion.US, "B001")
                    service.getBook(AudibleRegion.US, "B001")
                    audible.bookCalls shouldBe 1
                }
            }
        }

        test("getBook with refresh = true bypasses cache") {
            withSqlDatabase {
                val audible = FakeAudibleApi(bookResult = AppResult.Success(book("B001")))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    service.getBook(AudibleRegion.US, "B001")
                    service.getBook(AudibleRegion.US, "B001", refresh = true)
                    audible.bookCalls shouldBe 2
                }
            }
        }

        test("getBook caches null (404) to avoid repeated Audible hammering") {
            withSqlDatabase {
                val audible = FakeAudibleApi(bookResult = AppResult.Success(null))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    service.getBook(AudibleRegion.US, "MISSING")
                    service.getBook(AudibleRegion.US, "MISSING")
                    audible.bookCalls shouldBe 1
                    val result = service.getBook(AudibleRegion.US, "MISSING")
                    (result as AppResult.Success).data.shouldBeNull()
                }
            }
        }

        test("getBook is region-scoped — UK and US are independent cache entries") {
            withSqlDatabase {
                val audible = FakeAudibleApi(bookResult = AppResult.Success(book("B001")))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    service.getBook(AudibleRegion.US, "B001")
                    service.getBook(AudibleRegion.UK, "B001")
                    audible.bookCalls shouldBe 2 // different regions → different cache entries
                }
            }
        }

        // ── getBookInAnyRegion ─────────────────────────────────────────────────

        test("getBookInAnyRegion: a stub in the preferred region falls through to the store that sells it") {
            withSqlDatabase {
                val caBook = book("B071L4NKN4")
                val audible =
                    RegionAwareFakeAudibleApi(
                        bookResults =
                            mapOf(
                                AudibleRegion.US to AppResult.Success(null), // stub / catalog miss
                                AudibleRegion.CA to AppResult.Success(caBook),
                            ),
                    )
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    val result = service.getBookInAnyRegion("B071L4NKN4", preferred = AudibleRegion.US)
                    val regional = (result as AppResult.Success).data
                    regional.shouldNotBeNull()
                    regional.book shouldBe caBook
                    regional.region shouldBe AudibleRegion.CA
                    audible.bookCallOrder shouldBe listOf(AudibleRegion.US, AudibleRegion.CA)
                }
            }
        }

        test("getBookInAnyRegion: the preferred region answering first means no other store is asked") {
            withSqlDatabase {
                val caBook = book("B01")
                val audible = RegionAwareFakeAudibleApi(bookResults = mapOf(AudibleRegion.CA to AppResult.Success(caBook)))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    val result = service.getBookInAnyRegion("B01", preferred = AudibleRegion.CA)
                    val regional = (result as AppResult.Success).data
                    regional.shouldNotBeNull()
                    regional.region shouldBe AudibleRegion.CA
                    audible.bookCallOrder shouldBe listOf(AudibleRegion.CA)
                }
            }
        }

        test("getBookInAnyRegion: every store answering a stub converges on Success(null)") {
            withSqlDatabase {
                val audible = RegionAwareFakeAudibleApi(bookResults = emptyMap())
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    val result = service.getBookInAnyRegion("GHOST", preferred = AudibleRegion.US)
                    (result as AppResult.Success).data.shouldBeNull()
                    audible.bookCallOrder.size shouldBe AudibleRegion.entries.size
                    audible.bookCallOrder.toSet().size shouldBe AudibleRegion.entries.size // each store once
                }
            }
        }

        test("getBookInAnyRegion: a failure in the preferred store stops the walk and is returned as-is") {
            withSqlDatabase {
                val failure = AppResult.Failure(MetadataError.ExternalUnavailable())
                val audible =
                    RegionAwareFakeAudibleApi(
                        bookResults =
                            mapOf(
                                AudibleRegion.US to failure,
                                AudibleRegion.CA to AppResult.Success(book("B01")),
                            ),
                    )
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    val result = service.getBookInAnyRegion("B01", preferred = AudibleRegion.US)
                    result shouldBe failure
                    audible.bookCallOrder shouldBe listOf(AudibleRegion.US) // CA never asked
                }
            }
        }

        // ── getBookChapters ────────────────────────────────────────────────────

        test("getBookChapters caches the result; second call does not hit AudibleApi") {
            withSqlDatabase {
                val audible = FakeAudibleApi(chaptersResult = AppResult.Success(listOf(chapter("Intro"))))
                val service = makeService(audible = audible, db = sql, clock = FixedClock(now))
                runTest {
                    service.getBookChapters(AudibleRegion.US, "B001")
                    service.getBookChapters(AudibleRegion.US, "B001")
                    audible.chapterCalls shouldBe 1
                }
            }
        }

        // ── findCover ──────────────────────────────────────────────────────────

        test("findCover delegates directly to ITunesApi") {
            withSqlDatabase {
                val itunes =
                    FakeITunesApi(
                        coverResult = AppResult.Success(ITunesCoverHit("http://small.jpg", "http://big.jpg")),
                    )
                val service = makeService(itunes = itunes, db = sql, clock = FixedClock(now))
                runTest {
                    val result = service.findCover("Dune", "Frank Herbert")
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    (result as AppResult.Success).data.shouldNotBeNull()
                    itunes.findCoverCalls shouldBe 1
                }
            }
        }

        test("findCover does not cache — repeated calls always hit ITunesApi") {
            withSqlDatabase {
                val itunes = FakeITunesApi(coverResult = AppResult.Success(null))
                val service = makeService(itunes = itunes, db = sql, clock = FixedClock(now))
                runTest {
                    service.findCover("Dune", "Frank Herbert")
                    service.findCover("Dune", "Frank Herbert")
                    itunes.findCoverCalls shouldBe 2
                }
            }
        }
    })

// ─── Test helpers ─────────────────────────────────────────────────────────────

private fun makeService(
    audible: AudibleApi = FakeAudibleApi(),
    itunes: ITunesApi = FakeITunesApi(),
    db: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    defaultRegion: AudibleRegion = AudibleRegion.US,
    clock: kotlin.time.Clock = FixedClock(Instant.parse("2026-05-24T12:00:00Z")),
): MetadataService =
    MetadataService(
        audible = audible,
        itunes = itunes,
        cache = MetadataCacheRepository(db, clock),
        defaultRegion = defaultRegion,
        clock = clock,
    )

/** Minimal [AudibleBook] for test assertions. */
private fun book(asin: String) =
    AudibleBook(
        asin = asin,
        title = "Test Book",
        subtitle = "",
        authors = emptyList(),
        narrators = emptyList(),
        publisher = "",
        releaseDate = "",
        runtimeMinutes = 0,
        description = "",
        coverUrl = "",
        series = emptyList(),
        genres = emptyList(),
        language = "en",
        rating = 0f,
        ratingCount = 0,
    )

/** Minimal [AudibleSearchResult] for test assertions. */
private fun searchResult(asin: String) =
    AudibleSearchResult(
        asin = asin,
        title = "Test Book",
        subtitle = "",
        authors = emptyList(),
        narrators = emptyList(),
        coverUrl = "",
        runtimeMinutes = 0,
        releaseDate = "",
    )

/** Minimal [AudibleChapter] for test assertions. */
private fun chapter(title: String) = AudibleChapter(title = title, startMs = 0L, durationMs = 60_000L)

// ─── Hand-rolled fakes ────────────────────────────────────────────────────────

private open class FakeAudibleApi(
    var searchResult: AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList()),
    var bookResult: AppResult<AudibleBook?> = AppResult.Success(null),
    var chaptersResult: AppResult<List<AudibleChapter>> = AppResult.Success(emptyList()),
) : AudibleApi {
    var searchCalls = 0
    var bookCalls = 0
    var chapterCalls = 0

    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> =
        run {
            searchCalls++
            searchResult
        }

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> =
        run {
            bookCalls++
            bookResult
        }

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> =
        run {
            chapterCalls++
            chaptersResult
        }

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

/** [AudibleApi] fake whose [getBook] answer varies by region — a store not in [bookResults] is a stub. */
private class RegionAwareFakeAudibleApi(
    private val bookResults: Map<AudibleRegion, AppResult<AudibleBook?>>,
) : AudibleApi {
    val bookCallOrder = mutableListOf<AudibleRegion>()

    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList())

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> {
        bookCallOrder += region
        return bookResults[region] ?: AppResult.Success(null)
    }

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = AppResult.Success(emptyList())

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

private class FakeITunesApi(
    var coverResult: AppResult<ITunesCoverHit?> = AppResult.Success(null),
) : ITunesApi {
    var findCoverCalls = 0

    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> =
        run {
            findCoverCalls++
            coverResult
        }

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}
