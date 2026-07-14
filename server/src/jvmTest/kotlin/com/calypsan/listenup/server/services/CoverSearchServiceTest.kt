@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class CoverSearchServiceTest :
    FunSpec({
        // Probe stub: width=height=URL length, so options are distinguishable + deterministic.
        val probe: suspend (String) -> Pair<Int, Int>? = { url -> url.length to url.length }

        fun coverSource(
            providerId: MetadataProviderId,
            block: suspend () -> AppResult<List<CoverMeta>>,
        ) = object : CoverSource {
            override val id = providerId

            override suspend fun searchCovers(
                book: BookIdentity,
                locale: MetadataLocale,
            ): AppResult<List<CoverMeta>> = block()
        }

        fun registryOf(vararg sources: CoverSource) = MetadataProviderRegistry(sources.toList())

        test("flattens provider options in registration order with probed dimensions") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary("The Way of Kings", "Brandon Sanderson") },
                        registry =
                            registryOf(
                                coverSource(MetadataProviderId.AUDIBLE) {
                                    AppResult.Success(listOf(CoverMeta(url = "https://audible/cover.jpg", sourceKey = "B01ASIN")))
                                },
                                coverSource(MetadataProviderId.ITUNES) {
                                    AppResult.Success(listOf(CoverMeta(url = "https://i/7000.jpg", sourceKey = "999")))
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

        test("prefers maxSizeUrl over url when a provider exposes a distinct high-res rendition") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary("T", "A") },
                        registry =
                            registryOf(
                                coverSource(MetadataProviderId.ITUNES) {
                                    AppResult.Success(
                                        listOf(
                                            CoverMeta(
                                                url = "https://i/100.jpg",
                                                maxSizeUrl = "https://i/7000.jpg",
                                                sourceKey = "5",
                                            ),
                                        ),
                                    )
                                },
                            ),
                        probeDimensions = probe,
                    )
                val opts = (svc.searchCovers(BookId("book1"), region = null) as AppResult.Success).data
                opts[0].url shouldBe "https://i/7000.jpg"
            }
        }

        test("one provider failing still returns the others' options") {
            runTest {
                val svc =
                    CoverSearchService(
                        readBook = { BookSummary("T", "A") },
                        registry =
                            registryOf(
                                coverSource(MetadataProviderId.AUDIBLE) { error("audible down") },
                                coverSource(MetadataProviderId.ITUNES) {
                                    AppResult.Success(listOf(CoverMeta(url = "https://i/2.jpg", sourceKey = "5")))
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
                        registry =
                            registryOf(
                                coverSource(MetadataProviderId.AUDIBLE) {
                                    AppResult.Failure(MetadataError.ExternalUnavailable())
                                },
                                coverSource(MetadataProviderId.ITUNES) {
                                    AppResult.Success(listOf(CoverMeta(url = "https://i/2.jpg", sourceKey = "5")))
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
                        registry =
                            registryOf(
                                coverSource(MetadataProviderId.AUDIBLE) {
                                    AppResult.Success(listOf(CoverMeta(url = "https://a/c.jpg", sourceKey = "B1")))
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
                        registry = MetadataProviderRegistry(emptyList()),
                        probeDimensions = probe,
                    )
                svc
                    .searchCovers(BookId("missing"), region = null)
                    .shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
