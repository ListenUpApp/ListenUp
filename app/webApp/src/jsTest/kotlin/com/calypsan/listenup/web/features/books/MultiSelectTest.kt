package com.calypsan.listenup.web.features.books

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.library.LibraryPage
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

private fun threeBooks() =
    listOf(
        contractBook("b1", "The Way of Kings"),
        contractBook("b2", "Words of Radiance"),
        contractBook("b3", "Oathbringer"),
    )

@Suppress("LongParameterList")
private fun library(
    selecting: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    books: List<BookListItem> = threeBooks(),
    onOpenBook: (String) -> Unit = {},
    onToggleSelect: (String) -> Unit = {},
    onStartSelecting: (() -> Unit)? = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        LibraryPage(
            state = contractLibrary(books = books),
            onEvent = {},
            onOpenBook = onOpenBook,
            onSelectFacet = {},
            selecting = selecting,
            selectedIds = selectedIds,
            onToggleSelect = onToggleSelect,
            onStartSelecting = onStartSelecting,
        )
    }
    return host
}

private fun cards(host: HTMLElement) = host.querySelectorAll(".lib-card").asList().filterIsInstance<HTMLElement>()

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/**
 * Picking books out of the library grid.
 *
 * What these pin: selection is a *mode* — the same press opens a book or picks it depending on
 * which mode the grid is in, and the card says which job it is doing rather than leaving a screen
 * reader to guess. Arming selection is offered only when there is something to select.
 */
class MultiSelectTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("Select is offered on a library with books in it") {
            button(library(), "Select").shouldNotBeNull()
        }

        // Arming selection over an empty grid is an affordance whose only outcome is nothing.
        test("Select is absent on an empty library") {
            button(library(books = emptyList()), "Select").shouldBeNull()
        }

        test("Select is absent once selection is already on") {
            button(library(selecting = true), "Select").shouldBeNull()
        }

        test("arming selection reports it") {
            val started = mutableListOf<Unit>()
            val host = library(onStartSelecting = { started += Unit })

            button(host, "Select").shouldNotBeNull().click()
            awaitFrame()

            started.size shouldBe 1
        }

        // ⛔ One gesture, two jobs, decided by the mode. A second target on every tile is not an
        // option in a grid that routinely holds twelve hundred of them.
        test("a press opens a book normally, and picks it while selecting") {
            val opened = mutableListOf<String>()
            val toggled = mutableListOf<String>()

            cards(library(onOpenBook = { opened += it }))[1].click()
            awaitFrame()
            opened shouldContainExactly listOf("b2")

            cards(library(selecting = true, onToggleSelect = { toggled += it }))[1].click()
            awaitFrame()
            toggled shouldContainExactly listOf("b2")
        }

        test("a card opening a book is never toggled, and vice versa") {
            val opened = mutableListOf<String>()
            val toggled = mutableListOf<String>()

            cards(library(selecting = true, onOpenBook = { opened += it }, onToggleSelect = { toggled += it }))[0]
                .click()
            awaitFrame()

            opened shouldContainExactly emptyList()
            toggled shouldContainExactly listOf("b1")
        }

        // ⛔ While selecting, the card IS a checkbox. Announcing it as a button would tell a screen
        // reader user that it opens the book, which is exactly what it stops doing.
        test("a card says which job it is doing") {
            cards(library()).map { it.getAttribute("role") } shouldContainExactly
                listOf("button", "button", "button")
            cards(library(selecting = true)).map { it.getAttribute("role") } shouldContainExactly
                listOf("checkbox", "checkbox", "checkbox")
        }

        test("a selected card is announced as checked, and only that one") {
            val host = library(selecting = true, selectedIds = setOf("b2"))

            cards(host).map { it.getAttribute("aria-checked") } shouldContainExactly listOf("false", "true", "false")
        }

        // Colour alone is not a state: the tick is the half that survives a reader who cannot see
        // the outline.
        test("a tick appears on every card while selecting, filled only on the chosen ones") {
            val host = library(selecting = true, selectedIds = setOf("b3"))

            host.querySelectorAll(".lib-tick").asList().size shouldBe 3
            host.querySelectorAll(".lib-tick.on").asList().size shouldBe 1
        }

        test("no ticks are drawn while not selecting") {
            library().querySelectorAll(".lib-tick").asList().size shouldBe 0
        }

        test("the count reads in the singular for one book") {
            bookCountLabel(1) shouldBe "1 book"
            bookCountLabel(37) shouldBe "37 books"
        }

        test("the selection helpers read the mode") {
            SelectionMode.None.isActive() shouldBe false
            SelectionMode.None.selectedIds() shouldBe emptySet()
            SelectionMode.Active(setOf("b1")).isActive() shouldBe true
            SelectionMode.Active(setOf("b1")).selectedIds() shouldContainExactly setOf("b1")
        }
    })
