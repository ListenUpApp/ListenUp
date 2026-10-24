package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** Now Playing's "More ▾" on web (§5.5.7), built from the shared `PlayerMenus`: the rows, their order, Close book apart. */
class PlayerMoreMenuTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun menu(
            state: PlayerUiState.Loaded,
            chosen: MutableList<PlayerMenuItem> = mutableListOf(),
        ): HTMLElement = mounts.mount { PlayerMoreMenu(state = state, onItem = { item, _ -> chosen += item }) }

        suspend fun openMenu(host: HTMLElement) {
            (host.querySelector(".np-more button[aria-haspopup=menu]") as HTMLElement).click()
            awaitFrame()
        }

        fun items(host: HTMLElement): List<HTMLElement> = host.querySelectorAll(".menu-i").asList().map { it as HTMLElement }

        test("the rows, in order, with the boost's value and Close book destructive after a separator") {
            val host =
                menu(
                    wayOfKings(
                        series = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
                        firstPdfDocId = "d1",
                        boostDb = 6f,
                    ),
                )
            openMenu(host)
            items(host).map { it.textContent } shouldBe
                listOf("Go to book", "Go to series", "Open PDF ↗", "Volume boost · +6 dB", "Close book")
            items(host).last().classList.contains("is-danger") shouldBe true
            items(host).last().closest(".menu-g-danger").shouldNotBeNull()
        }

        test("with no series, no PDF and no boost: Go to book, Volume boost · Off, Close book") {
            val host = menu(wayOfKings(series = emptyList()))
            openMenu(host)
            items(host).map { it.textContent } shouldBe listOf("Go to book", "Volume boost · Off", "Close book")
        }

        test("Open PDF is a link to the document, in a new tab") {
            val host = menu(wayOfKings(bookId = "b7", firstPdfDocId = "d1"))
            openMenu(host)
            val pdf = host.querySelector("a[href='/api/v1/books/b7/documents/d1']") as HTMLAnchorElement
            pdf.target shouldBe "_blank"
            pdf.rel shouldContain "noopener"
        }

        test("the trigger is named for the book, not just More") {
            val host = menu(wayOfKings())
            host.querySelector("[aria-label='More actions for The Way of Kings']").shouldNotBeNull()
        }

        test("each row reports its item: Go to series names the first series, Close book reports once") {
            val chosen = mutableListOf<PlayerMenuItem>()
            val host = menu(wayOfKings(series = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1"))), chosen)
            openMenu(host)
            items(host)[1].click()
            openMenu(host)
            items(host).last().click()
            chosen shouldBe listOf(PlayerMenuItem.GoToSeries("s1"), PlayerMenuItem.CloseBook)
        }
    })
