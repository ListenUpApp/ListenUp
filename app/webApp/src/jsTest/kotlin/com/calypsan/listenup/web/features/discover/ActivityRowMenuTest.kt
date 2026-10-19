package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** Every activity row's actions are one visible ⋯ away (a9), from one model shared with the natives (C7). */
class ActivityRowMenuTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("a book row offers the book, the person and add to a shelf, in that order") {
            activityRowActions(act("a1", "finished_book", bookId = "b1")) shouldBe
                listOf(
                    ActivityRowAction.OpenBook("b1"),
                    ActivityRowAction.OpenProfile("u1"),
                    ActivityRowAction.AddToShelf("b1"),
                )
            activityRowActions(act("s1", "shelf_created", bookId = null, shelfId = "sh1", shelfName = "Sci-fi")) shouldBe
                listOf(ActivityRowAction.OpenShelf("sh1"), ActivityRowAction.OpenProfile("u1"))
            activityRowActions(act("j1", "user_joined", bookId = null)).shouldBeEmpty()
        }

        test("a book row has a visible ⋯ named for the person, opening the three board actions") {
            val host =
                mounts.mount {
                    discoverPage(activity = feed(act("a1", "finished_book")), selection = selection(isSelecting = false))
                }
            val more = host.querySelector(".disc-feed-row [aria-haspopup=menu]") as HTMLElement
            more.getAttribute("aria-label") shouldBe "More actions for Ada's activity"
            more.tagName.lowercase() shouldBe "button"
            more.click()
            awaitFrame()
            host.querySelectorAll("[role=menuitem]").asList().map { it.textContent } shouldBe
                listOf("Open The Institute", "Ada's profile", "Add The Institute to a shelf")
        }

        test("your own row's ⋯ says it is yours") {
            val host = mounts.mount { discoverPage(activity = feed(act("a1", "finished_book", isYou = true))) }
            (host.querySelector("[aria-haspopup=menu]") as HTMLElement).getAttribute("aria-label") shouldBe
                "More actions for your activity"
        }

        test("a join row has no ⋯, since its link already opens the person") {
            val host = mounts.mount { discoverPage(activity = feed(act("a1", "user_joined", bookId = null))) }
            host.querySelectorAll(".disc-feed-row [aria-haspopup=menu]").length shouldBe 0
        }

        test("the ⋯ is never inside either of the row's links") {
            val host = mounts.mount { discoverPage(activity = feed(act("a1", "finished_book"))) }
            host.querySelectorAll(".disc-feed-row a [aria-haspopup=menu]").length shouldBe 0
        }

        test("Add to a shelf shelves that one book") {
            var shelved: String? = null
            val host =
                mounts.mount {
                    discoverPage(
                        activity = feed(act("a1", "finished_book", bookId = "b1")),
                        selection = selection(isSelecting = false, onShelveOne = { shelved = it }),
                    )
                }
            val more = host.querySelector("[aria-haspopup=menu]") as HTMLElement
            // Enter on a focused native button is a click; the menu button is one.
            more.focus()
            more.click()
            awaitFrame()
            (host.querySelectorAll("[role=menuitem]").item(2) as HTMLElement).click()
            shelved shouldBe "b1"
        }

        test("while a selection is under way, the menu leaves Add to a shelf out, so it cannot wipe the picks") {
            var shelved: String? = null
            val host =
                mounts.mount {
                    discoverPage(
                        activity = feed(act("a1", "finished_book")),
                        selection = selection(isSelecting = true, selected = setOf("p0"), onShelveOne = { shelved = it }),
                    )
                }
            (host.querySelector("[aria-haspopup=menu]") as HTMLElement).click()
            awaitFrame()
            host.querySelectorAll("[role=menuitem]").asList().map { it.textContent } shouldBe
                listOf("Open The Institute", "Ada's profile")
            shelved shouldBe null
        }

        test("with nowhere to shelve, the menu leaves Add to a shelf out") {
            val host = mounts.mount { discoverPage(activity = feed(act("a1", "finished_book")), selection = null) }
            (host.querySelector("[aria-haspopup=menu]") as HTMLElement).click()
            awaitFrame()
            host.querySelectorAll("[role=menuitem]").asList().map { it.textContent } shouldBe
                listOf("Open The Institute", "Ada's profile")
        }
    })
