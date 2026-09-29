package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.contributordetail.RoleTile
import com.calypsan.listenup.web.features.contributordetail.bookItem
import com.calypsan.listenup.web.features.library.BookCard
import com.calypsan.listenup.web.features.seriesdetail.SeriesDetailPage
import com.calypsan.listenup.web.features.seriesdetail.readySeries
import com.calypsan.listenup.web.features.seriesdetail.seriesBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

/**
 * A book with no artwork looks the same on every page it appears on.
 *
 * Library, a contributor's tiles and a series' rows each drew their own `<img>` and their own
 * coverless tile — three different fallbacks, so one book without a cover changed colour and shape as
 * the reader moved between pages. All three are the shared `Cover` now.
 */
class CoverFallbackTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("Library, a contributor's tile and a series row fall back to the same cover") {
            val library = mounts.mount { BookCard(book = bookItem("b1", TITLE), progress = 0f, onOpen = {}) }
            val contributor = mounts.mount { RoleTile(book = bookItem("b1", TITLE), progress = null, onOpen = {}) }
            val series =
                mounts.mount {
                    SeriesDetailPage(
                        state = readySeries(books = listOf(seriesBook("b1", TITLE, 1.0))),
                        onOpenLibrary = {},
                        onOpenBook = {},
                    )
                }
            val hosts = listOf(library, contributor, series.querySelector(".sd-book") as HTMLElement)

            // The server has no artwork: every cover's request fails.
            hosts.forEach { host -> host.querySelectorAll(".cover img").asList().forEach { it.dispatchEvent(Event("error")) } }
            awaitFrame()

            val covers = hosts.map { it.querySelector(".cover") as HTMLElement }
            covers.forEach { it.querySelector("img") shouldBe null }
            // Seeded by the title, so the same book draws the same tile, whichever page it is on.
            val fills = covers.map { it.style.backgroundImage }
            fills.first() shouldContain "linear-gradient"
            fills.toSet() shouldHaveSize 1
            // Where a tile is big enough to hold the title, it is set the same way on both pages.
            val library0 = covers[0].querySelector("span") as HTMLElement
            val contributor0 = covers[1].querySelector("span") as HTMLElement
            library0.textContent shouldBe TITLE
            library0.style.cssText shouldBe contributor0.style.cssText
            library0.getAttribute("aria-hidden") shouldNotBe null
        }
    })

private const val TITLE = "The Way of Kings"
