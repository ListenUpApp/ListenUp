package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.presentation.library.LibraryUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/**
 * A book card is a click target, so it owes the same affordance to a reader who is not using a
 * mouse.
 *
 * It was a bare `Div` with an `onClick`: not focusable, so unreachable by keyboard and invisible to
 * the tab order — which also meant a focus ring had nothing to attach to. These tests pin the
 * activation half; the visual half is CSS and lives in `web.css`.
 */
class BookCardActivationTest :
    FunSpec({

        fun render(
            state: LibraryUiState,
            onOpenBook: (String) -> Unit,
        ): HTMLElement {
            val root = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            renderComposable(root = root) {
                LibraryPage(state = state, onEvent = {}, onOpenBook = { onOpenBook(it) }, onSelectFacet = {})
            }
            return root
        }

        fun EventTarget.press(key: String) {
            dispatchEvent(
                KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)),
            )
        }

        test("a book card is reachable by keyboard") {
            val root = render(contractLibrary(listOf(contractBook("b1", "Dune"))), onOpenBook = {})

            val card = root.querySelector(".lib-card") as HTMLElement
            card.getAttribute("tabindex") shouldBe "0"
        }

        test("a book card announces itself as a control, not a decoration") {
            val root = render(contractLibrary(listOf(contractBook("b1", "Dune"))), onOpenBook = {})

            root.querySelector(".lib-card")!!.getAttribute("role") shouldBe "button"
        }

        test("Enter opens the book the card is for") {
            val opened = mutableListOf<String>()
            val root =
                render(
                    contractLibrary(listOf(contractBook("b1", "Dune"), contractBook("b2", "Ubik"))),
                    onOpenBook = { opened += it },
                )

            root.querySelectorAll(".lib-card").item(1)!!.press("Enter")

            opened shouldBe listOf("b2")
        }

        test("Space opens the book too, as a button would") {
            val opened = mutableListOf<String>()
            val root = render(contractLibrary(listOf(contractBook("b1", "Dune"))), onOpenBook = { opened += it })

            root.querySelector(".lib-card")!!.press(" ")

            opened shouldBe listOf("b1")
        }

        test("a key that is not an activation leaves the card alone") {
            // Arrow keys scroll the grid; swallowing them would trap the reader on one card.
            val opened = mutableListOf<String>()
            val root = render(contractLibrary(listOf(contractBook("b1", "Dune"))), onOpenBook = { opened += it })

            root.querySelector(".lib-card")!!.press("ArrowDown")

            opened shouldBe emptyList()
        }
    })
