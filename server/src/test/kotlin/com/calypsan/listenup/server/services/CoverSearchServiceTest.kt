@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class CoverSearchServiceTest :
    FunSpec({
        // Probe stub: width=height=URL length, so options are distinguishable + deterministic.
        val probe: suspend (String) -> Pair<Int, Int>? = { url -> url.length to url.length }

        fun audibleHit() =
            AudibleSearchResult(
                asin = "B01ASIN",
                title = "The Way of Kings",
                subtitle = "",
                authors = listOf(AudibleContributor("a1", "Brandon Sanderson")),
                narrators = emptyList(),
                coverUrl = "https://audible/cover.jpg",
                runtimeMinutes = 100,
                releaseDate = "2010",
            )

        test("merges Audible (first) + iTunes options with probed dimensions") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary(title = "The Way of Kings", author = "Brandon Sanderson") },
                        audibleSearch = { _, _ -> AppResult.Success(listOf(audibleHit())) },
                        itunesSearch = { _, _ ->
                            AppResult.Success(listOf(ITunesCoverHit("https://i/100.jpg", "https://i/7000.jpg", "999")))
                        },
                        probeDimensions = probe,
                    )
                val result = svc.searchCovers(BookId("book1"), region = AudibleRegion.US)

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val opts = (result as AppResult.Success).data
                opts.map { it.source } shouldBe listOf(CoverOptionSource.AUDIBLE, CoverOptionSource.ITUNES)
                opts[0].url shouldBe "https://audible/cover.jpg"
                opts[0].sourceId shouldBe "B01ASIN"
                opts[1].url shouldBe "https://i/7000.jpg"
                opts[1].sourceId shouldBe "999"
                opts[1].width shouldBe "https://i/7000.jpg".length
            }
        }

        test("one source failing still returns the other's options") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary("T", "A") },
                        audibleSearch = { _, _ -> error("audible down") },
                        itunesSearch = { _, _ ->
                            AppResult.Success(listOf(ITunesCoverHit("https://i/1.jpg", "https://i/2.jpg", "5")))
                        },
                        probeDimensions = probe,
                    )
                val result = svc.searchCovers(BookId("book1"), region = null)
                val opts = (result as AppResult.Success).data
                opts.map { it.source } shouldBe listOf(CoverOptionSource.ITUNES)
            }
        }

        test("book not found is a typed failure") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { null },
                        audibleSearch = { _, _ -> AppResult.Success(emptyList()) },
                        itunesSearch = { _, _ -> AppResult.Success(emptyList()) },
                        probeDimensions = probe,
                    )
                svc.searchCovers(BookId("missing"), region = null)
                    .shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
