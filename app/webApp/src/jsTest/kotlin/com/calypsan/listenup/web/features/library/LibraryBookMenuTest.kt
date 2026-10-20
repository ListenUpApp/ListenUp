package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.client.presentation.library.BookMenuPrimary
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit

/**
 * A Library book's menu on web (board `Library-WebDesktop-ContextMenu`): the board's order, the admin group, and a
 * right-click that opens the menu instead of the book.
 */
class LibraryBookMenuTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val calls = mutableListOf<String>()
        beforeTest { calls.clear() }

        fun recordingActions() =
            LibraryBookMenuActions(
                onPrimary = { calls += "play" },
                onAddToShelf = { calls += "shelf" },
                onShare = { calls += "share" },
                onSelect = { calls += "select" },
                onAddToCollection = { calls += "collection" },
                onEditDetails = { calls += "edit" },
            )

        fun source(isAdmin: Boolean = false) =
            LibraryBookMenuSource(
                isAdmin = isAdmin,
                onPlay = { calls += "play:$it" },
                onShare = { id, title -> calls += "share:$id:$title" },
                onSelectOne = { calls += "select:$it" },
                onShelveOne = { calls += "shelf:$it" },
                onCollectOne = { calls += "collection:$it" },
                onEdit = { calls += "edit:$it" },
            )

        fun menuLabels(): List<String?> = document.querySelectorAll("[role=menu] .menu-i").asList().map { it.textContent?.trim() }

        test("a member's menu is the board's four, Resume first with its time left") {
            libraryBookMenuItems(primary = BookMenuPrimary.Resume(21_720_000L), isAdmin = false, actions = recordingActions())
                .map { it.label } shouldBe listOf("Resume · 6h 2m left", "Add to shelf…", "Share…", "Select")
        }

        test("an admin's ends with the Admin group: Add to collection… and Edit details") {
            val items = libraryBookMenuItems(primary = BookMenuPrimary.Play, isAdmin = true, actions = recordingActions())

            items.map { it.label } shouldBe
                listOf("Play", "Add to shelf…", "Share…", "Select", "Add to collection…", "Edit details")
            items.filter { it.group == "Admin" }.map { it.label } shouldBe listOf("Add to collection…", "Edit details")
        }

        test("a finished book is listened to again") {
            libraryBookMenuItems(BookMenuPrimary.ListenAgain, isAdmin = false, actions = recordingActions())
                .first()
                .label shouldBe "Listen again"
        }

        test("right-clicking a card opens its menu and does not open the book") {
            val opened = mutableListOf<String>()
            val books = listOf(contractBook("b1", "Dune"))
            val root =
                mounts.mount {
                    LibraryPage(
                        state = contractLibrary(books),
                        onEvent = {},
                        onOpenBook = { opened += it },
                        onSelectFacet = {},
                        bookMenu = source(),
                    )
                }

            val card = root.querySelector(".lib-card") as HTMLElement
            val event = MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true, clientX = 20, clientY = 20))
            card.dispatchEvent(event)
            awaitFrame()

            event.defaultPrevented shouldBe true
            opened shouldBe emptyList()
            // contractLibrary marks every book part-read, so it leads with Resume.
            menuLabels().first()!!.startsWith("Resume ·") shouldBe true
            menuLabels().drop(1) shouldContainExactly listOf("Add to shelf…", "Share…", "Select")
        }

        test("a card with a menu says so to assistive technology, and one without does not") {
            val withMenu =
                mounts.mount {
                    LibraryPage(
                        state = contractLibrary(listOf(contractBook("b1", "Dune"))),
                        onEvent = {},
                        onOpenBook = {},
                        onSelectFacet = {},
                        bookMenu = source(),
                    )
                }
            (withMenu.querySelector(".lib-card") as HTMLElement).getAttribute("aria-haspopup") shouldBe "menu"

            val without =
                mounts.mount {
                    LibraryPage(
                        state = contractLibrary(listOf(contractBook("b1", "Dune"))),
                        onEvent = {},
                        onOpenBook = {},
                        onSelectFacet = {},
                    )
                }
            (without.querySelector(".lib-card") as HTMLElement).getAttribute("aria-haspopup") shouldBe null
        }

        test("choosing Select starts selecting with that book") {
            val root =
                mounts.mount {
                    LibraryPage(
                        state = contractLibrary(listOf(contractBook("b1", "Dune"))),
                        onEvent = {},
                        onOpenBook = {},
                        onSelectFacet = {},
                        bookMenu = source(),
                    )
                }

            (
                root.querySelector(
                    ".lib-card",
                ) as HTMLElement
            ).dispatchEvent(MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true)))
            awaitFrame()
            (document.querySelectorAll("[role=menu] .menu-i").asList().first { it.textContent?.trim() == "Select" } as HTMLElement).click()

            calls shouldBe listOf("select:b1")
        }

        test("Share passes the book's title, and an admin's Edit details its id") {
            val root =
                mounts.mount {
                    LibraryPage(
                        state =
                            contractLibrary(listOf(contractBook("b1", "Dune"))).let { state ->
                                state.copy(bookStatus = mapOf(state.books[0].id to BookCardStatus.NotStarted(durationMs = 1L)))
                            },
                        onEvent = {},
                        onOpenBook = {},
                        onSelectFacet = {},
                        bookMenu = source(isAdmin = true),
                    )
                }
            val card = root.querySelector(".lib-card") as HTMLElement

            card.dispatchEvent(MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true)))
            awaitFrame()
            menuLabels() shouldContainExactly
                listOf("Play", "Add to shelf…", "Share…", "Select", "Add to collection…", "Edit details")
            (document.querySelectorAll("[role=menu] .menu-i").asList().first { it.textContent?.trim() == "Share…" } as HTMLElement).click()
            awaitFrame()
            card.dispatchEvent(MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true)))
            awaitFrame()
            (
                document
                    .querySelectorAll(
                        "[role=menu] .menu-i",
                    ).asList()
                    .first { it.textContent?.trim() == "Edit details" } as HTMLElement
            ).click()

            calls shouldBe listOf("share:b1:Dune", "edit:b1")
        }

        test("while selecting, a card has no menu: it is a checkbox") {
            val root =
                mounts.mount {
                    LibraryPage(
                        state = contractLibrary(listOf(contractBook("b1", "Dune"))),
                        onEvent = {},
                        onOpenBook = {},
                        onSelectFacet = {},
                        selecting = true,
                        bookMenu = source(),
                    )
                }

            val event = MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true))
            (root.querySelector(".lib-card") as HTMLElement).dispatchEvent(event)
            awaitFrame()

            event.defaultPrevented shouldBe false
            document.querySelectorAll("[role=menu]").length shouldBe 0
        }
    })
