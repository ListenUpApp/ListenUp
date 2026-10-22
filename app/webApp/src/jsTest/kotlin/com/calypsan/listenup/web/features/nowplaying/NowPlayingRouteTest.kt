package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.awaitPopstate
import com.calypsan.listenup.web.mountAt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/** S4: `/playing` is a page; with nothing loaded it goes Home, but never while the loaded book is still restoring. */
class NowPlayingRouteTest :
    FunSpec({
        test("a deep link with a book loaded opens the page, headed Now playing") {
            val (host, router, composition) = mountAt("/playing", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                host.querySelector(".np-page h1")?.textContent shouldBe "Now playing"
                window.location.pathname shouldBe "/playing"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("with nothing loaded, /playing goes Home, replacing the entry") {
            val (_, router, composition) =
                mountAt("/playing", openPlayback = fixedPlayback(state = PlayerUiState.NothingLoaded))
            try {
                awaitFrame()
                awaitFrame()
                window.location.pathname shouldBe "/"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("while the loaded book is still restoring, it waits rather than bouncing Home") {
            val loaded = MutableStateFlow<LoadedBook>(LoadedBook.Restoring)
            val (host, router, composition) =
                mountAt(
                    "/playing",
                    openPlayback = fixedPlayback(state = PlayerUiState.NothingLoaded, loadedBook = loaded),
                )
            try {
                awaitFrame()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                host.querySelector(".np-page") shouldBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("on /playing the shell goes immersive, so a phone hides the tabs, the header and the bar") {
            val (host, router, composition) = mountAt("/playing", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                host.querySelector(".shell-immersive").shouldNotBeNull()
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    
        test("the bar's Open goes to /playing, and Close comes back with focus on Open") {
            val (host, router, composition) = mountAt("/library", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                val open = host.querySelector(".tport-expand") as HTMLElement
                open.focus()
                open.click()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                (host.querySelector(".tport-expand") as HTMLElement).getAttribute("aria-expanded") shouldBe "true"
                (host.querySelector(".tport-expand") as HTMLElement).click()
                awaitPopstate()
                awaitFrame()
                window.location.pathname shouldBe "/library"
                document.activeElement shouldBe host.querySelector(".tport-expand")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the bar's body is a link that opens the page in one click") {
            val (host, router, composition) = mountAt("/library", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                (host.querySelector("a.tport-body") as HTMLElement).click()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                host.querySelector(".np-page").shouldNotBeNull()
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Esc on /playing goes back, but not while focus is in a field") {
            val (host, router, composition) = mountAt("/library", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                (host.querySelector(".tport-body") as HTMLElement).click()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                val search = document.createElement("input") as HTMLInputElement
                host.appendChild(search)
                search.focus()
                search.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                search.remove()
                document.body!!.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
                awaitPopstate()
                window.location.pathname shouldBe "/library"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Esc stands down while a dialog is open over the page") {
            val (host, router, composition) = mountAt("/playing", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                val dialog = document.createElement("dialog") as HTMLElement
                dialog.setAttribute("open", "")
                host.appendChild(dialog)
                document.body!!.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                dialog.remove()
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a deep link to /playing closes to Home, not out of the app") {
            val (host, router, composition) = mountAt("/playing", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                (host.querySelector(".np-collapse") as HTMLElement).click()
                awaitFrame()
                window.location.pathname shouldBe "/"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
