package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.LocalScrollport
import com.calypsan.listenup.web.design.Scrollport
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The Books A–Z jump rail (spec §5.2): real buttons named "Jump to S", reading the shared letter
 * sections, that bring a section to the top and put focus on its first book.
 */
class JumpRailTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("each letter is a button named Jump to it, once each, in list order") {
            val root =
                mounts.mount {
                    JumpRail(letters = railLetters(listOf(LetterSection("#", 0, 1), LetterSection("B", 1, 1), LetterSection("#", 2, 1)))) {}
                }
            val buttons = root.querySelectorAll(".lib-rail button").asList().map { it as HTMLElement }
            buttons.map { it.textContent } shouldBe listOf("#", "B")
            buttons.map { it.getAttribute("aria-label") } shouldBe listOf("Jump to #", "Jump to B")
            root.querySelector(".lib-rail")!!.getAttribute("aria-label") shouldBe "Jump to letter"
        }

        test("a single letter offers no rail") {
            mounts.mount { JumpRail(letters = listOf("A")) {} }.querySelector(".lib-rail") shouldBe null
        }

        test("pressing a letter scrolls its section into view and focuses its first book") {
            val books = (1..40).map { contractBook("a$it", "Alpha $it") } + (1..40).map { contractBook("z$it", "Zulu $it") }
            val letters = listOf(LetterSection("A", 0, 40), LetterSection("Z", 40, 40))
            val root =
                mounts.mount {
                    Div(attrs = {
                        classes("scroll-host")
                        style {
                            property("height", "300px")
                            property("overflow", "auto")
                        }
                    }) {
                        BooksWithRail(books = books, letters = letters, statusOf = { null }, onOpenBook = {})
                    }
                }
            val host = root.querySelector(".scroll-host") as HTMLElement
            host.scrollTop shouldBe 0.0

            (root.querySelector(".lib-rail button[aria-label=\"Jump to Z\"]") as HTMLElement).click()
            awaitFrame()

            (host.scrollTop > 0.0) shouldBe true
            (document.activeElement as HTMLElement).textContent!!.contains("Zulu 1") shouldBe true
        }

        // The shell's own case: inside its scrollport the grid virtualises, so the section jumped to
        // is not even rendered yet. The spec above mounts outside any shell and never virtualises.
        test("inside the shell's scrollport, where the grid virtualises, a letter still jumps and focuses") {
            val books = (1..150).map { contractBook("a$it", "Alpha $it") } + (1..150).map { contractBook("z$it", "Zulu $it") }
            val letters = listOf(LetterSection("A", 0, 150), LetterSection("Z", 150, 150))
            val port = Scrollport()
            val root =
                mounts.mount {
                    Div(attrs = {
                        classes("shell-main")
                        style {
                            property("height", "400px")
                            property("overflow", "auto")
                        }
                        ref { element ->
                            port.element = element
                            onDispose { port.element = null }
                        }
                    }) {
                        CompositionLocalProvider(LocalScrollport provides port) {
                            BooksWithRail(books = books, letters = letters, statusOf = { null }, onOpenBook = {})
                        }
                    }
                }
            repeat(SETTLE_FRAMES) { awaitFrame() }
            val host = root.querySelector(".shell-main") as HTMLElement
            host.scrollTop shouldBe 0.0
            root.querySelector("[data-section=\"Z\"]") shouldBe null

            (root.querySelector(".lib-rail button[aria-label=\"Jump to Z\"]") as HTMLElement).click()
            repeat(SETTLE_FRAMES) { awaitFrame() }

            (host.scrollTop > 0.0) shouldBe true
            (document.activeElement as HTMLElement).textContent!!.contains("Zulu 1") shouldBe true
        }

        test("letter headers carry their caption") {
            val books = listOf(contractBook("a1", "Alpha"), contractBook("a2", "Amber"), contractBook("z1", "Zulu"))
            val root =
                mounts.mount {
                    BooksWithRail(
                        books = books,
                        letters = listOf(LetterSection("A", 0, 2), LetterSection("Z", 2, 1)),
                        statusOf = { null },
                        onOpenBook = {},
                    )
                }
            root.querySelectorAll(".lib-section").asList().map { it.textContent } shouldBe listOf("A2 books", "Z1 book")
        }

        test("no letters: no headers and no rail") {
            val root =
                mounts.mount {
                    BooksWithRail(books = listOf(contractBook("a1", "Alpha")), letters = emptyList(), statusOf = { null }, onOpenBook = {})
                }
            root.querySelectorAll(".lib-section").length shouldBe 0
            root.querySelector(".lib-rail") shouldBe null
        }
    })

/** Frames for a virtualised grid to measure itself, or to render the rows a scroll brought in. */
private const val SETTLE_FRAMES = 6
