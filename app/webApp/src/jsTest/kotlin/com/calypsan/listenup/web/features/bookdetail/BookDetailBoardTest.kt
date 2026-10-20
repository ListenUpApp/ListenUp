package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** Book Detail drawn to its boards: one panel heading style, chips clear of the synopsis, the Files arrow unlined. */
class BookDetailBoardTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        suspend fun render(): HTMLElement {
            val root =
                mounts.mount {
                    BookDetailPage(state = readyBook(), onPlayFromChapter = {}, onOpenLibrary = {}, onPlay = {}, onRetryConnection = {})
                }
            awaitFrame()
            return root
        }

        fun headingOf(
            root: HTMLElement,
            text: String,
        ): HTMLElement =
            root.querySelectorAll("h2").let { all ->
                (0 until all.length).map { all.item(it) as HTMLElement }.first { it.textContent.orEmpty().startsWith(text) }
            }

        test("Chapters and About carry the board's large heading, bare, with no hairline under it") {
            val root = render()
            // The board: 26 px on a desktop, 22 px on a phone, weight 800; the card panels keep their small heading.
            val large = if (window.innerWidth >= 640) "26px" else "22px"
            listOf("Chapters", "About").forEach { name ->
                val heading = headingOf(root, name)
                val style = window.getComputedStyle(heading)
                style.fontSize shouldBe large
                style.fontWeight shouldBe "800"
                window.getComputedStyle(heading.parentElement as HTMLElement).borderBottomWidth shouldBe "0px"
                // Not a card: the section itself draws no border (the board's Chapters card wraps only the rows).
                window.getComputedStyle(heading.closest("section") as HTMLElement).borderTopWidth shouldBe "0px"
            }
            window.getComputedStyle(headingOf(root, "Details")).fontSize shouldBe "14px"
        }

        test("the genre chips sit clear of the synopsis") {
            val root = render()
            val desc = (root.querySelector(".bd-desc") as HTMLElement).getBoundingClientRect()
            val chips = (root.querySelector(".bd-facets") as HTMLElement).getBoundingClientRect()
            (chips.top - desc.bottom >= 11.5) shouldBe true
        }

        test("the Files & audio row's link is underlined, its arrow is not") {
            val root = render()
            val link = root.querySelector("a.bd-detail-link[href='/book/42/files']") as HTMLElement
            window.getComputedStyle(link).textDecorationLine shouldBe "underline"
            val arrow = link.querySelector("[aria-hidden]") as HTMLElement
            arrow.textContent shouldBe "→"
            window.getComputedStyle(arrow).textDecorationLine shouldBe "none"
        }
    })
