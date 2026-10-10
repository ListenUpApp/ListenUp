package com.calypsan.listenup.web.motion

import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailPage
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import com.calypsan.listenup.web.features.contributors.ContributorsPage
import com.calypsan.listenup.web.features.contributors.contributor
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.seriesdetail.SeriesDetailPage
import com.calypsan.listenup.web.features.seriesdetail.readySeries
import com.calypsan.listenup.web.features.serieslist.SeriesListPage
import com.calypsan.listenup.web.motions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement

private val STORMLIGHT =
    contractBook("b1", "The Way of Kings").let { first ->
        SeriesWithBooks(
            series = Series(id = SeriesId("s-stormlight"), name = "The Stormlight Archive"),
            books = listOf(first),
            bookSequences = mapOf(first.id.value to null),
        )
    }

/**
 * The two new shared elements, wired on their real pages: a series card's cover ↔ the series hero,
 * and a contributor row's avatar ↔ the contributor hero. Each journey is the router's in miniature —
 * the source page goes, then the destination arrives.
 */
class SharedElementFlightsTest :
    FunSpec({
        val mounts = MountRegistry()

        afterTest {
            mounts.disposeAll()
            forgetHeroFlight()
        }

        fun seriesList(): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    SeriesListPage(
                        state = contractLibrary(series = listOf(STORMLIGHT)),
                        onEvent = {},
                        onOpenSeries = {},
                        onSelectFacet = {},
                    )
                }
            }

        fun seriesDetail(): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    SeriesDetailPage(state = readySeries(seriesId = "s-stormlight"), onOpenLibrary = {}, onOpenBook = {})
                }
            }

        fun contributorsList(): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    ContributorsPage(
                        state = listOf(contributor("c-king", "Stephen King")),
                        role = ContributorRole.AUTHOR,
                        onSelectFacet = {},
                        onOpenContributor = {},
                    )
                }
            }

        fun contributorDetail(): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    ContributorDetailPage(
                        state = readyContributor(),
                        onOpenLibrary = {},
                        onOpenContributors = {},
                        onOpenBook = {},
                        onConfirmDelete = {},
                        onDismissDeleteError = {},
                    )
                }
            }

        test("a series card's cover flies into the series hero") {
            (seriesList().querySelector(".srs-card") as HTMLElement).click()
            mounts.disposeAll()

            val detail = seriesDetail()
            awaitFrame()

            (detail.querySelector(".sd-head .cover") as HTMLElement).motions().size shouldBe 1
        }

        test("leaving a series sends its cover back to its card") {
            seriesDetail()
            awaitFrame()
            captureHeroOriginBeforeRouteChange()
            mounts.disposeAll()

            val list = seriesList()
            awaitFrame()

            (list.querySelector(".srs-cover") as HTMLElement).motions().size shouldBe 1
        }

        test("a contributor's avatar flies into their page") {
            (contributorsList().querySelector(".contrib-row") as HTMLElement).click()
            mounts.disposeAll()

            val detail = contributorDetail()
            awaitFrame()

            (detail.querySelector(".cd-avatar") as HTMLElement).motions().size shouldBe 1
        }

        test("leaving a contributor sends the avatar back to their row") {
            contributorDetail()
            awaitFrame()
            captureHeroOriginBeforeRouteChange()
            mounts.disposeAll()

            val list = contributorsList()
            awaitFrame()

            (list.querySelector(".contrib-avatar") as HTMLElement).motions().size shouldBe 1
        }
    })
