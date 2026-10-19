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

        test("the Chapters heading is styled like every other panel's") {
            val root = render()
            val chapters = window.getComputedStyle(headingOf(root, "Chapters"))
            val about = window.getComputedStyle(headingOf(root, "About"))
            chapters.fontSize shouldBe about.fontSize
            chapters.fontWeight shouldBe about.fontWeight
            val chaptersHeader = window.getComputedStyle(headingOf(root, "Chapters").parentElement as HTMLElement)
            val aboutHeader = window.getComputedStyle(headingOf(root, "About").parentElement as HTMLElement)
            chaptersHeader.borderBottomWidth shouldBe aboutHeader.borderBottomWidth
            chaptersHeader.height shouldBe aboutHeader.height
        }

        test("the genre chips sit clear of the synopsis") {
            val root = render()
            val desc = (root.querySelector(".bd-desc") as HTMLElement).getBoundingClientRect()
            val chips = (root.querySelector(".bd-facets") as HTMLElement).getBoundingClientRect()
            (chips.top - desc.bottom >= 11.5) shouldBe true
        }

        test("Files & audio is underlined, its arrow is not") {
            val root = render()
            val link = root.querySelector("a.bd-files-link") as HTMLElement
            window.getComputedStyle(link).textDecorationLine shouldBe "none"
            val label = link.querySelector(".bd-files-label") as HTMLElement
            label.textContent shouldBe "Files & audio"
            window.getComputedStyle(label).textDecorationLine shouldBe "underline"
        }
    })
