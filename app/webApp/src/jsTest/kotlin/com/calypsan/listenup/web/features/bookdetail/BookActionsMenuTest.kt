package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private fun menuItems(host: HTMLElement): List<String> =
    host
        .querySelectorAll(".menu-i")
        .asList()
        .filterIsInstance<HTMLElement>()
        .map { it.textContent.orEmpty().trim() }

private const val CANCEL = 0

private const val CONFIRM = 1

private fun dialogButton(
    host: HTMLElement,
    index: Int,
): HTMLElement = host.querySelectorAll("dialog.dlg .dlg-actions button").item(index) as HTMLElement

private fun dialogTitle(host: HTMLElement): String? = host.querySelector("dialog.dlg .dlg-t")?.textContent

private fun dialogBody(host: HTMLElement): String? = host.querySelector("dialog.dlg .dlg-p")?.textContent

private fun item(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll(".menu-i")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/**
 * The actions a reader can take on their own copy of a book.
 *
 * ⛔ Web's Book Detail was **read-only** until this existed: the page consumed `state` and offered
 * no action, so a reader could see they were 40% through a book and had no way to say they had
 * finished it, abandoned it, or wanted to begin again. All three functions have been on
 * `BookDetailViewModel` the whole time, wired on both natives and on neither web surface.
 */
class BookActionsMenuTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun menu(
            ready: BookDetailUiState.Ready,
            onMarkComplete: () -> Unit = {},
            onDiscardProgress: () -> Unit = {},
            onRestart: () -> Unit = {},
            onAddToShelf: () -> Unit = {},
            onAddToCollection: () -> Unit = {},
            onShare: () -> Unit = {},
            onDeleteBook: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookActionsMenu(
                    ready = ready,
                    onMarkComplete = onMarkComplete,
                    onDiscardProgress = onDiscardProgress,
                    onRestart = onRestart,
                    onAddToShelf = onAddToShelf,
                    onAddToCollection = onAddToCollection,
                    onShare = onShare,
                    onDeleteBook = onDeleteBook,
                )
            }

        suspend fun openMenu(host: HTMLElement): HTMLElement {
            (host.querySelector(".btn-icon") as HTMLElement).click()
            awaitFrame()
            return host
        }

        test("an untouched book offers only the one action that would do anything") {
            // Nothing to clear and nothing to restart — offering either would be a control that
            // changes nothing.
            val host = openMenu(menu(readyBook()))

            menuItems(host) shouldContainExactly listOf("Mark as finished", "Add to shelf", "Share")
        }

        test("a book in progress offers all three") {
            val host = openMenu(menu(readyBook().copy(progress = 0.4f)))

            menuItems(host) shouldContainExactly
                listOf("Mark as finished", "Mark as not started", "Restart book", "Add to shelf", "Share")
        }

        test("a finished book can still be undone, even with no progress left to key on") {
            // ⛔ Both clear actions key on `progress != null || isComplete`. A finished book has no
            // in-progress position, so keying on progress alone would strand a reader who marked a
            // book finished by mistake.
            val host = openMenu(menu(readyBook().copy(isComplete = true, progress = null)))

            menuItems(host) shouldContainExactly listOf("Mark as not started", "Restart book", "Add to shelf", "Share")
        }

        test("each action reports itself — the two that erase progress only once confirmed") {
            var completed = 0
            var discarded = 0
            var restarted = 0
            val host =
                openMenu(
                    menu(
                        readyBook().copy(progress = 0.4f),
                        onMarkComplete = { completed++ },
                        onDiscardProgress = { discarded++ },
                        onRestart = { restarted++ },
                    ),
                )

            item(host, "Mark as finished").shouldNotBeNull().click()
            awaitFrame()
            completed shouldBe 1

            openMenu(host)
            item(host, "Mark as not started").shouldNotBeNull().click()
            awaitFrame()
            discarded shouldBe 0
            dialogButton(host, CONFIRM).click()
            awaitFrame()
            discarded shouldBe 1

            openMenu(host)
            item(host, "Restart book").shouldNotBeNull().click()
            awaitFrame()
            restarted shouldBe 0
            dialogButton(host, CONFIRM).click()
            awaitFrame()
            restarted shouldBe 1
        }

        test("Mark as not started asks first, in the natives' words") {
            // ⛔ Both natives confirm this; web used to clear a reader's progress on one stray click.
            val host = openMenu(menu(readyBook().copy(progress = 0.4f)))

            item(host, "Mark as not started").shouldNotBeNull().click()
            awaitFrame()

            dialogTitle(host) shouldBe "Mark as not started"
            dialogBody(host) shouldBe "Clear your progress and mark this as not started?"
            dialogButton(host, CONFIRM).textContent shouldBe "Mark as not started"
        }

        test("Restart book asks first, in the natives' words") {
            val host = openMenu(menu(readyBook().copy(progress = 0.4f)))

            item(host, "Restart book").shouldNotBeNull().click()
            awaitFrame()

            dialogTitle(host) shouldBe "Restart book"
            dialogBody(host) shouldBe
                "Start over from the beginning? This resets your position to the start of the book."
            dialogButton(host, CONFIRM).textContent shouldBe "Restart book"
        }

        test("cancelling either confirmation changes nothing") {
            var discarded = 0
            var restarted = 0
            val host =
                openMenu(
                    menu(
                        readyBook().copy(progress = 0.4f),
                        onDiscardProgress = { discarded++ },
                        onRestart = { restarted++ },
                    ),
                )

            item(host, "Mark as not started").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, CANCEL).click()
            awaitFrame()
            host.querySelector("dialog.dlg").shouldBeNull()

            openMenu(host)
            item(host, "Restart book").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, CANCEL).click()
            awaitFrame()

            discarded shouldBe 0
            restarted shouldBe 0
            host.querySelector("dialog.dlg").shouldBeNull()
        }

        test("an admin is offered Delete book, last, and choosing it asks for the dialog") {
            var asked = 0
            val host = openMenu(menu(readyBook().copy(isAdmin = true), onDeleteBook = { asked++ }))

            menuItems(host).last() shouldBe "Delete book…"
            item(host, "Delete book…").shouldNotBeNull().click()
            awaitFrame()

            asked shouldBe 1
        }

        test("a member is never offered Delete book") {
            // ⛔ Deleting removes a folder from the server's disk. Admin-gated on every client and
            // refused server-side too — the menu must not even suggest it to a member.
            val host = openMenu(menu(readyBook().copy(isAdmin = false)))

            item(host, "Delete book…").shouldBeNull()
        }

        test("choosing an action closes the menu") {
            val host = openMenu(menu(readyBook()))

            item(host, "Mark as finished").shouldNotBeNull().click()
            awaitFrame()

            host.querySelector(".menu").shouldBeNull()
        }

        test("the menu is closed until it is asked for") {
            menu(readyBook()).querySelector(".menu").shouldBeNull()
        }

        test("items are real menu items, not clickable text") {
            // ⛔ The account menu renders its items as <div onClick>, which no keyboard can reach
            // and no screen reader announces. A new menu does not get to inherit that.
            val host = openMenu(menu(readyBook().copy(progress = 0.4f)))

            val items = host.querySelectorAll(".menu-i").asList().filterIsInstance<HTMLElement>()
            // Three progress actions, two filing ones, and Share — which is always offered.
            items.size shouldBe 5
            items.forEach {
                it.tagName.lowercase() shouldBe "button"
                it.getAttribute("role") shouldBe "menuitem"
            }
            (host.querySelector(".menu") as HTMLElement).getAttribute("role") shouldBe "menu"
        }

        test("a request in flight holds the menu shut") {
            // ⛔ One busy flag for all three: they are the same round-trip through the same
            // repository, and a second request would race the first to the same position record.
            listOf(
                readyBook().copy(isMarkingComplete = true),
                readyBook().copy(isDiscardingProgress = true),
                readyBook().copy(isRestarting = true),
            ).forEach { state ->
                val host = menu(state)
                (host.querySelector(".btn-icon") as HTMLButtonElement).hasAttribute("disabled") shouldBe true
            }
        }

        test("the trigger says whether it is open, for a reader who cannot see it") {
            val host = menu(readyBook())

            (host.querySelector(".btn-icon") as HTMLElement).getAttribute("aria-expanded") shouldBe "false"
            openMenu(host)
            (host.querySelector(".btn-icon") as HTMLElement).getAttribute("aria-expanded") shouldBe "true"
        }
    })
