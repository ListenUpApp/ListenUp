@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.metadata.provider.CoverCandidate
import com.calypsan.listenup.server.metadata.provider.CoverProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class CoverSearchServiceTest :
    FunSpec({
        // Probe stub: width=height=URL length, so options are distinguishable + deterministic.
        val probe: suspend (String) -> Pair<Int, Int>? = { url -> url.length to url.length }

        fun provider(
            src: CoverOptionSource,
            block: suspend () -> AppResult<List<CoverCandidate>>,
        ) = object : CoverProvider {
            override val source = src
            override suspend fun searchCovers(
                book: BookSummary,
                region: AudibleRegion?,
            ): AppResult<List<CoverCandidate>> = block()
        }

        test("flattens provider options in list order with probed dimensions") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary("The Way of Kings", "Brandon Sanderson") },
                        providers =
                            listOf(
                                provider(CoverOptionSource.AUDIBLE) {
                                    AppResult.Success(listOf(CoverCandidate("https://audible/cover.jpg", "B01ASIN")))
                                },
                                provider(CoverOptionSource.ITUNES) {
                                    AppResult.Success(listOf(CoverCandidate("https://i/7000.jpg", "999")))
                                },
                            ),
                        probeDimensions = probe,
                    )
                val opts = (svc.searchCovers(BookId("book1"), AudibleRegion.US) as AppResult.Success).data

                opts.map { it.source } shouldBe listOf(CoverOptionSource.AUDIBLE, CoverOptionSource.ITUNES)
                opts[0].url shouldBe "https://audible/cover.jpg"
                opts[0].sourceId shouldBe "B01ASIN"
                opts[1].url shouldBe "https://i/7000.jpg"
                opts[1].sourceId shouldBe "999"
                opts[1].width shouldBe "https://i/7000.jpg".length
            }
        }

        test("one provider failing still returns the others' options") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary("T", "A") },
                        providers =
                            listOf(
                                provider(CoverOptionSource.AUDIBLE) { error("audible down") },
                                provider(CoverOptionSource.ITUNES) {
                                    AppResult.Success(listOf(CoverCandidate("https://i/2.jpg", "5")))
                                },
                            ),
                        probeDimensions = probe,
                    )
                val opts = (svc.searchCovers(BookId("book1"), region = null) as AppResult.Success).data
                opts.map { it.source } shouldBe listOf(CoverOptionSource.ITUNES)
            }
        }

        test("a provider returning a typed Failure is contained, not fatal") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary("T", "A") },
                        providers =
                            listOf(
                                provider(CoverOptionSource.AUDIBLE) {
                                    AppResult.Failure(com.calypsan.listenup.api.error.MetadataError.ExternalUnavailable())
                                },
                                provider(CoverOptionSource.ITUNES) {
                                    AppResult.Success(listOf(CoverCandidate("https://i/2.jpg", "5")))
                                },
                            ),
                        probeDimensions = probe,
                    )
                val opts = (svc.searchCovers(BookId("book1"), region = null) as AppResult.Success).data
                opts.map { it.source } shouldBe listOf(CoverOptionSource.ITUNES)
            }
        }

        test("probe miss degrades to 0×0 rather than dropping the cover") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary("T", "A") },
                        providers =
                            listOf(
                                provider(CoverOptionSource.AUDIBLE) {
                                    AppResult.Success(listOf(CoverCandidate("https://a/c.jpg", "B1")))
                                },
                            ),
                        probeDimensions = { null },
                    )
                val opts = (svc.searchCovers(BookId("book1"), region = null) as AppResult.Success).data
                opts.size shouldBe 1
                opts[0].width shouldBe 0
                opts[0].height shouldBe 0
            }
        }

        test("book not found is a typed failure") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { null },
                        providers = emptyList(),
                        probeDimensions = probe,
                    )
                svc.searchCovers(BookId("missing"), region = null)
                    .shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
