@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ITunesProviderTest :
    FunSpec({
        fun api(result: AppResult<List<ITunesCoverHit>>) =
            object : ITunesApi {
                override suspend fun findCover(
                    title: String,
                    author: String,
                ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

                override suspend fun searchCovers(
                    title: String,
                    author: String,
                ): AppResult<List<ITunesCoverHit>> = result
            }

        val identity = BookIdentity(title = "The Way of Kings", primaryAuthor = "Brandon Sanderson")

        test("id is ITUNES") {
            ITunesProvider(api(AppResult.Success(emptyList()))).id shouldBe MetadataProviderId.ITUNES
        }

        test("maps hits to CoverMeta in order, keeping url + maxSizeUrl, dropping blank max renditions") {
            runTest {
                val provider =
                    ITunesProvider(
                        api(
                            AppResult.Success(
                                listOf(
                                    ITunesCoverHit("https://i/100.jpg", "https://i/7000.jpg", "111"),
                                    ITunesCoverHit("https://i/100b.jpg", "", "222"),
                                    ITunesCoverHit("https://i/100c.jpg", "https://i/7000c.jpg", "333"),
                                ),
                            ),
                        ),
                    )
                val data =
                    provider
                        .searchCovers(identity, MetadataLocale.DEFAULT)
                        .shouldBeInstanceOf<AppResult.Success<List<CoverMeta>>>()
                        .data

                data shouldBe
                    listOf(
                        CoverMeta(url = "https://i/100.jpg", maxSizeUrl = "https://i/7000.jpg", sourceKey = "111"),
                        CoverMeta(url = "https://i/100c.jpg", maxSizeUrl = "https://i/7000c.jpg", sourceKey = "333"),
                    )
            }
        }

        test("propagates a provider failure") {
            runTest {
                ITunesProvider(api(AppResult.Failure(MetadataError.ExternalRateLimited())))
                    .searchCovers(identity, MetadataLocale.DEFAULT)
                    .shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
