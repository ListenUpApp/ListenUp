@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.services.BookSummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class AudibleCoverProviderTest :
    FunSpec({
        fun hit(asin: String, cover: String) =
            AudibleSearchResult(
                asin = asin,
                title = "T",
                subtitle = "",
                authors = listOf(AudibleContributor("a1", "Author")),
                narrators = emptyList(),
                coverUrl = cover,
                runtimeMinutes = 10,
                releaseDate = "2020",
            )

        test("source is AUDIBLE") {
            AudibleCoverProvider(search = { _, _ -> AppResult.Success(emptyList()) }).source shouldBe
                CoverOptionSource.AUDIBLE
        }

        test("emits the first non-blank cover as a single candidate") {
            runTest {
                val provider =
                    AudibleCoverProvider(
                        search = { _, _ ->
                            AppResult.Success(listOf(hit("B1", ""), hit("B2", "https://a/cover.jpg")))
                        },
                    )
                val result = provider.searchCovers(BookSummary("T", "A"), region = null)
                val data = result.shouldBeInstanceOf<AppResult.Success<List<CoverCandidate>>>().data
                data shouldBe listOf(CoverCandidate(url = "https://a/cover.jpg", sourceId = "B2"))
            }
        }

        test("empty when no result has a cover") {
            runTest {
                val provider =
                    AudibleCoverProvider(search = { _, _ -> AppResult.Success(listOf(hit("B1", ""))) })
                provider.searchCovers(BookSummary("T", "A"), region = null)
                    .shouldBeInstanceOf<AppResult.Success<List<CoverCandidate>>>()
                    .data shouldBe emptyList()
            }
        }

        test("propagates a provider failure") {
            runTest {
                val provider =
                    AudibleCoverProvider(
                        search = { _, _ ->
                            AppResult.Failure(com.calypsan.listenup.api.error.MetadataError.ExternalUnavailable())
                        },
                    )
                provider.searchCovers(BookSummary("T", "A"), region = null)
                    .shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
