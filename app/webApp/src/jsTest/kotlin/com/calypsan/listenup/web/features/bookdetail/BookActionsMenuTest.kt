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
        ): HTMLElement =
            mounts.mount {
                BookActionsMenu(
                    ready = ready,
                    onMarkComplete = onMarkComplete,
                    onDiscardProgress = onDiscardProgress,
                    onRestart = onRestart,
                )
            }

        suspend fun openMenu(host: HTMLElement): HTMLElement {
            (host.querySelector(".btn-sq") as HTMLElement).click()
            awaitFrame()
            return host
        }

        test("an untouched book offers only the one action that would do anything") {
            // Nothing to clear and nothing to restart — offering either would be a control that
            // changes nothing.
            val host = openMenu(menu(readyBook()))

            menuItems(host) shouldContainExactly listOf("Mark as finished")
        }

        test("a book in progress offers all three") {
            val host = openMenu(menu(readyBook().copy(progress = 0.4f)))

            menuItems(host) shouldContainExactly
                listOf("Mark as finished", "Mark as not started", "Restart book")
        }

        test("a finished book can still be undone, even with no progress left to key on") {
            // ⛔ Both clear actions key on `progress != null || isComplete`. A finished book has no
            // in-progress position, so keying on progress alone would strand a reader who marked a
            // book finished by mistake.
            val host = openMenu(menu(readyBook().copy(isComplete = true, progress = null)))

            menuItems(host) shouldContainExactly listOf("Mark as not started", "Restart book")
        }

        test("each action reports itself") {
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
            discarded shouldBe 1

            openMenu(host)
            item(host, "Restart book").shouldNotBeNull().click()
            awaitFrame()
            restarted shouldBe 1
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
            items.size shouldBe 3
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
                (host.querySelector(".btn-sq") as HTMLButtonElement).hasAttribute("disabled") shouldBe true
            }
        }

        test("the trigger says whether it is open, for a reader who cannot see it") {
            val host = menu(readyBook())

            (host.querySelector(".btn-sq") as HTMLElement).getAttribute("aria-expanded") shouldBe "false"
            openMenu(host)
            (host.querySelector(".btn-sq") as HTMLElement).getAttribute("aria-expanded") shouldBe "true"
        }
    })
