package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindRole
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.metadata.spi.FoundBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/** iTunes in Find (matching redesign PR 2): one more cover for a candidate, never a candidate of its own. */
class ITunesProviderFindTest :
    FunSpec({
        test("iTunes only attaches, searches what Find searches, and drops hits it can't name") {
            val asked = mutableListOf<String>()
            val itunes =
                object : ITunesApi {
                    override suspend fun findCover(
                        title: String,
                        author: String,
                    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

                    override suspend fun searchCovers(
                        title: String,
                        author: String,
                    ): AppResult<List<ITunesCoverHit>> {
                        asked += "$title|$author"
                        return AppResult.Success(
                            listOf(
                                ITunesCoverHit(
                                    coverUrl = "https://i/100.jpg",
                                    maxSizeUrl = "https://i/7000.jpg",
                                    sourceId = "111",
                                    title = "Project Hail Mary",
                                    author = "Andy Weir",
                                ),
                                ITunesCoverHit("https://i/x.jpg", "https://i/x7.jpg", "", title = "No id", author = "Nobody"),
                            ),
                        )
                    }
                }
            val provider = ITunesProvider(itunes)
            provider.findRole shouldBe FindRole.ATTACHES

            runTest {
                val lookup =
                    FindLookup("b1", true, emptyList(), null, null, "hail mary weir", "Project Hail Mary", "Andy Weir")
                val answer =
                    provider.findBooks(lookup, MetadataLocale.DEFAULT).shouldBeInstanceOf<AppResult.Success<FindAnswer>>().data

                asked shouldBe listOf("hail mary weir|")
                answer.steps shouldBe setOf(FindStep.TEXT)
                answer.books shouldBe
                    listOf(
                        FoundBook(
                            key = "111",
                            title = "Project Hail Mary",
                            authors = listOf("Andy Weir"),
                            coverUrl = "https://i/7000.jpg",
                        ),
                    )
            }
        }
    })
