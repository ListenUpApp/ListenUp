@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.services.BookSummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ITunesCoverProviderTest :
    FunSpec({
        test("source is ITUNES") {
            ITunesCoverProvider(search = { _, _ -> AppResult.Success(emptyList()) }).source shouldBe
                CoverOptionSource.ITUNES
        }

        test("maps each hit with a non-blank maxSizeUrl to a candidate, dropping blanks") {
            runTest {
                val provider =
                    ITunesCoverProvider(
                        search = { _, _ ->
                            AppResult.Success(
                                listOf(
                                    ITunesCoverHit("https://i/100.jpg", "https://i/7000.jpg", "111"),
                                    ITunesCoverHit("https://i/100b.jpg", "", "222"),
                                ),
                            )
                        },
                    )
                val data =
                    provider
                        .searchCovers(BookSummary("T", "A"), region = null)
                        .shouldBeInstanceOf<AppResult.Success<List<CoverCandidate>>>()
                        .data
                data shouldBe listOf(CoverCandidate(url = "https://i/7000.jpg", sourceId = "111"))
            }
        }

        test("propagates a provider failure") {
            runTest {
                val provider =
                    ITunesCoverProvider(
                        search = { _, _ ->
                            AppResult.Failure(
                                com.calypsan.listenup.api.error.MetadataError
                                    .ExternalRateLimited(),
                            )
                        },
                    )
                provider
                    .searchCovers(BookSummary("T", "A"), region = null)
                    .shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
