package com.calypsan.listenup.web.features.shelf

import io.kotest.matchers.nulls.shouldNotBeNull
import com.calypsan.listenup.web.awaitFrame
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private val mountedHosts = mutableListOf<HTMLElement>()

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    mountedHosts += host
    renderComposable(root = host) { content() }
    return host
}

private fun book(
    id: String,
    title: String,
) = ShelfBook(
    id = BookId(id),
    title = title,
    authorNames = listOf("Stephen King"),
    coverPath = null,
    coverHash = null,
)

private fun ready(
    books: List<ShelfBook>,
    isOwner: Boolean = true,
    isPrivate: Boolean = false,
    description: String? = "Books to fall asleep to.",
) = ShelfDetailUiState.Ready(
    detail =
        ShelfDetail(
            id = ShelfId("s1"),
            name = "Comfort reads",
            description = description,
            isPrivate = isPrivate,
            isOwner = isOwner,
            bookCount = books.size,
            totalDurationSeconds = 7_200,
            books = books,
        ),
    isOwner = isOwner,
)

/**
 * A drag, as the browser actually delivers it: `dragstart` on the row picked up, then `drop` on the
 * row landed on. Synthesised rather than driven by a real pointer because a headless browser has no
 * drag gesture — but the events are the same ones the page listens for.
 */
private fun dragRowOnto(
    host: HTMLElement,
    from: Int,
    to: Int,
) {
    val rows = host.querySelectorAll(".shelf-book")
    (rows.item(from) as HTMLElement).dispatchEvent(Event("dragstart", eventInit()))
    (rows.item(to) as HTMLElement).dispatchEvent(Event("drop", eventInit()))
}

private fun eventInit(): dynamic {
    val init: dynamic = js("({})")
    init.bubbles = true
    init.cancelable = true
    return init
}

private fun pressKey(
    element: HTMLElement,
    key: String,
) {
    element.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
}

/**
 * The shelf screens: what an owner can do that a visitor cannot, and where a drag lands.
 *
 * The reorder arithmetic itself is pinned in [ShelfOrderTest]; these specs are about the page
 * calling it with the right pair of indices, and about the controls existing only for an owner.
 */
class ShelfPagesTest :
    FunSpec({

        afterSpec {
            mountedHosts.forEach { it.remove() }
            mountedHosts.clear()
        }

        test("a visitor sees the books and none of the controls") {
            // Ownership is the server's answer. A visitor rendering disabled controls would be
            // advertising an ability they do not have.
            val host =
                mount {
                    ShelfDetailPage(ready(listOf(book("b1", "Dune")), isOwner = false), null, {}, {}, {}, {}, {}, {})
                }

            host.textContent.orEmpty() shouldContain "Dune"
            host.querySelectorAll(".shelf-grip").length shouldBe 0
            host.querySelectorAll(".shelf-book-x").length shouldBe 0
            host.textContent.orEmpty() shouldNotContain "Edit shelf"
        }

        test("an owner gets a grip and a remove control on every book") {
            val host =
                mount {
                    ShelfDetailPage(ready(listOf(book("b1", "Dune"), book("b2", "Piranesi"))), null, {}, {}, {}, {}, {}, {})
                }

            host.querySelectorAll(".shelf-grip").length shouldBe 2
            host.querySelectorAll(".shelf-book-x").length shouldBe 2
            host.textContent.orEmpty() shouldContain "Edit shelf"
        }

        test("dragging a book onto a later row sends the order it landed in") {
            var ordered: List<String>? = null
            val host =
                mount {
                    ShelfDetailPage(
                        ready(listOf(book("a", "A"), book("b", "B"), book("c", "C"))),
                        null,
                        {},
                        {},
                        {},
                        { ordered = it },
                        {},
                        {},
                    )
                }

            dragRowOnto(host, from = 0, to = 2)

            // Not ["b","a","c"] — that is the off-by-one a downward drag invites.
            ordered shouldBe listOf("b", "c", "a")
        }

        test("dragging a book onto an earlier row sends the order it landed in") {
            var ordered: List<String>? = null
            val host =
                mount {
                    ShelfDetailPage(
                        ready(listOf(book("a", "A"), book("b", "B"), book("c", "C"))),
                        null,
                        {},
                        {},
                        {},
                        { ordered = it },
                        {},
                        {},
                    )
                }

            dragRowOnto(host, from = 2, to = 0)

            ordered shouldBe listOf("c", "a", "b")
        }

        test("dropping a book back where it started asks for nothing") {
            // A reorder that changes nothing is still a write, a sync frame and a revision bump.
            var calls = 0
            val host =
                mount {
                    ShelfDetailPage(
                        ready(listOf(book("a", "A"), book("b", "B"))),
                        null,
                        {},
                        {},
                        {},
                        { calls++ },
                        {},
                        {},
                    )
                }

            dragRowOnto(host, from = 1, to = 1)

            calls shouldBe 0
        }

        test("the grip moves a book with the arrow keys, so a reorder is not mouse-only") {
            var ordered: List<String>? = null
            val host =
                mount {
                    ShelfDetailPage(
                        ready(listOf(book("a", "A"), book("b", "B"), book("c", "C"))),
                        null,
                        {},
                        {},
                        {},
                        { ordered = it },
                        {},
                        {},
                    )
                }

            val grips = host.querySelectorAll(".shelf-grip")
            pressKey(grips.item(1) as HTMLElement, "ArrowUp")
            ordered shouldBe listOf("b", "a", "c")

            pressKey(grips.item(0) as HTMLElement, "ArrowDown")
            ordered shouldBe listOf("b", "a", "c")
        }

        test("arrowing off either end of the shelf does nothing rather than wrapping") {
            // Wrapping would move the top book silently to the bottom, which is never what someone
            // holding the up arrow meant.
            var calls = 0
            val host =
                mount {
                    ShelfDetailPage(
                        ready(listOf(book("a", "A"), book("b", "B"))),
                        null,
                        {},
                        {},
                        {},
                        { calls++ },
                        {},
                        {},
                    )
                }

            val grips = host.querySelectorAll(".shelf-grip")
            pressKey(grips.item(0) as HTMLElement, "ArrowUp")
            pressKey(grips.item(1) as HTMLElement, "ArrowDown")

            calls shouldBe 0
        }

        test("removing a book names that book to a screen reader, and reports it") {
            var removed: String? = null
            val host =
                mount {
                    ShelfDetailPage(
                        ready(listOf(book("a", "A"), book("b", "Piranesi"))),
                        null,
                        {},
                        {},
                        { removed = it },
                        {},
                        {},
                        {},
                    )
                }

            val remove = host.querySelectorAll(".shelf-book-x").item(1) as HTMLElement
            remove.getAttribute("aria-label") shouldBe "Remove Piranesi from this shelf"
            remove.click()

            removed shouldBe "b"
        }

        test("a refused mutation is said out loud, not just logged") {
            // The regression this exists for: ShelfDetailViewModel has always pushed a message into
            // a channel on failure, and nothing on web read it — so a reorder the server rejected
            // reverted the list and explained nothing.
            val host =
                mount {
                    ShelfDetailPage(
                        ready(listOf(book("a", "A"))),
                        "Could not reorder this shelf.",
                        {},
                        {},
                        {},
                        {},
                        {},
                        {},
                    )
                }

            host.textContent.orEmpty() shouldContain "Could not reorder this shelf."
        }

        test("a shelf with nothing to report renders no notice at all") {
            val host = mount { ShelfDetailPage(ready(listOf(book("a", "A"))), null, {}, {}, {}, {}, {}, {}) }

            host.querySelector(".shelf-notice") shouldBe null
        }

        test("dismissing the notice reports it once") {
            var dismissed = 0
            val host =
                mount {
                    ShelfDetailPage(
                        ready(listOf(book("a", "A"))),
                        "Could not reorder this shelf.",
                        { dismissed++ },
                        {},
                        {},
                        {},
                        {},
                        {},
                    )
                }

            (host.querySelector(".shelf-notice-x") as HTMLElement).click()

            dismissed shouldBe 1
        }

        test("an empty shelf explains itself rather than showing an empty frame") {
            val host = mount { ShelfDetailPage(ready(emptyList()), null, {}, {}, {}, {}, {}, {}) }

            host.textContent.orEmpty() shouldContain "This shelf is empty"
        }

        test("private is said out loud; shared is not") {
            // Every shelf that is not private is shared, so labelling the common case spends a word
            // on every shelf to inform nobody.
            val privateHost =
                mount { ShelfDetailPage(ready(listOf(book("a", "A")), isPrivate = true), null, {}, {}, {}, {}, {}, {}) }
            val sharedHost =
                mount { ShelfDetailPage(ready(listOf(book("a", "A")), isPrivate = false), null, {}, {}, {}, {}, {}, {}) }

            privateHost.textContent.orEmpty() shouldContain "Private"
            sharedHost.textContent.orEmpty() shouldNotContain "Private"
        }

        // ── the create/edit form ─────────────────────────────────────────────────

        test("a shelf cannot be saved without a name") {
            val host = mount { ShelfEditPage(CreateEditShelfUiState.Idle, false, { _, _, _ -> }, {}, {}, {}) }

            val save = host.querySelector("button[type=submit]") as HTMLElement
            save.getAttribute("disabled") shouldBe ""
        }

        test("editing seeds the form from the shelf, once") {
            val host =
                mount {
                    ShelfEditPage(
                        CreateEditShelfUiState.Loaded("Comfort reads", "Sleepy books", true),
                        true,
                        { _, _, _ -> },
                        {},
                        {},
                        {},
                    )
                }

            (host.querySelector("input[type=text]") as HTMLInputElement).value shouldBe "Comfort reads"
            // `.value`, not `.textContent`: a textarea's textContent is its initial markup, which
            // stays empty for a value Compose set as a property.
            (host.querySelector("textarea") as HTMLTextAreaElement).value shouldBe "Sleepy books"
            (host.querySelector("input[type=checkbox]") as HTMLInputElement).checked shouldBe true
        }

        test("creating offers no way to delete something that does not exist yet") {
            val host = mount { ShelfEditPage(CreateEditShelfUiState.Idle, false, { _, _, _ -> }, {}, {}, {}) }

            host.textContent.orEmpty() shouldNotContain "Delete shelf"
        }

        test("deleting asks first, in a dialog, and says what survives") {
            var deleted = 0
            val host =
                mount { ShelfEditPage(CreateEditShelfUiState.Idle, true, { _, _, _ -> }, { deleted++ }, {}, {}) }

            (host.querySelector(".shelf-danger button") as HTMLElement).click()
            awaitFrame()

            deleted shouldBe 0
            // The two-step inline confirm this replaced is gone; the question now arrives in the
            // shared dialog, so there is one shape of "are you sure" in the app rather than two.
            val dialog = host.querySelector("dialog.dlg").shouldNotBeNull()
            dialog.textContent.orEmpty() shouldContain "The books stay in your library."
        }

        test("confirming in the dialog is what deletes") {
            var deleted = 0
            val host =
                mount { ShelfEditPage(CreateEditShelfUiState.Idle, true, { _, _, _ -> }, { deleted++ }, {}, {}) }

            (host.querySelector(".shelf-danger button") as HTMLElement).click()
            awaitFrame()
            // Cancel is first in the DOM so a hurried Return lands on the safe choice; the confirm
            // is the second.
            (host.querySelectorAll("dialog.dlg .dlg-actions button").item(1) as HTMLElement).click()

            deleted shouldBe 1
        }

        test("cancelling the dialog leaves the shelf alone") {
            var deleted = 0
            val host =
                mount { ShelfEditPage(CreateEditShelfUiState.Idle, true, { _, _, _ -> }, { deleted++ }, {}, {}) }

            (host.querySelector(".shelf-danger button") as HTMLElement).click()
            awaitFrame()
            (host.querySelectorAll("dialog.dlg .dlg-actions button").item(0) as HTMLElement).click()
            awaitFrame()

            deleted shouldBe 0
            host.querySelector("dialog.dlg") shouldBe null
        }
    })
