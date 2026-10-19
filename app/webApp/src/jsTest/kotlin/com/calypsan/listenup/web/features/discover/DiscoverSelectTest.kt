package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/**
 * Select on Discover (X35, gap 32): Something new's header starts a selection, and while selecting
 * every book tile is a real checkbox whose state is the string "true" or "false" (DSw).
 */
class DiscoverSelectTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("Select in Something new's header starts selection, and the header then reads Selecting") {
            var started = 0
            val host =
                mounts.mount { discoverPage(books = picks(3), selection = selection(isSelecting = false, onStart = { started++ })) }
            val select = host.querySelector(".disc-a-new .disc-select") as HTMLElement
            select.textContent shouldBe "Select"
            select.click()
            started shouldBe 1

            val selecting = mounts.mount { discoverPage(books = picks(3), selection = selection(isSelecting = true)) }
            selecting.querySelector(".disc-a-new .disc-selecting")!!.textContent shouldBe "Selecting"
            selecting.querySelectorAll(".disc-a-new .disc-select").length shouldBe 0
        }

        test("while selecting, every Discover tile is a checkbox whose state is the string true or false") {
            val host =
                mounts.mount {
                    discoverPage(
                        books = picks(2),
                        recentlyAdded = recent(1),
                        currentlyListening = readyListening(listOf(listenerOf("u9", live = true))),
                        selection = selection(isSelecting = true, selected = setOf("p0")),
                    )
                }
            val boxes = host.querySelectorAll("[role=checkbox]").asList().map { it as HTMLElement }
            boxes.size shouldBe 4
            boxes.map { it.getAttribute("aria-checked") }.toSet() shouldBe setOf("true", "false")
            boxes.none { it.hasAttribute("checked") } shouldBe true
            boxes.all { it.tabIndex == 0 } shouldBe true
        }

        test("the listener's person link stays a link while selecting") {
            val host =
                mounts.mount {
                    discoverPage(
                        currentlyListening = readyListening(listOf(listenerOf("u9", live = true))),
                        selection = selection(isSelecting = true),
                    )
                }
            (host.querySelector(".disc-listener .disc-person") as HTMLElement).tagName.lowercase() shouldBe "a"
        }

        test("Space on a focused tile toggles it") {
            var toggled: String? = null
            val host =
                mounts.mount { discoverPage(books = picks(1), selection = selection(isSelecting = true, onToggle = { toggled = it })) }
            val tile = host.querySelector("[role=checkbox]") as HTMLElement
            tile.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = " ", bubbles = true, cancelable = true)))
            toggled shouldBe "p0"
        }

        test("Select is offered only once there is something to select") {
            val host =
                mounts.mount { discoverPage(books = DiscoverBooksUiState.AllStarted, selection = selection(isSelecting = false)) }
            host.querySelectorAll(".disc-select").length shouldBe 0
        }

        test("a tile not selecting stays one link to the book") {
            val host = mounts.mount { discoverPage(books = picks(1), selection = selection(isSelecting = false)) }
            host.querySelectorAll("[role=checkbox]").length shouldBe 0
            (host.querySelector(".disc-a-new .disc-card") as HTMLElement).tagName.lowercase() shouldBe "a"
        }
    })
