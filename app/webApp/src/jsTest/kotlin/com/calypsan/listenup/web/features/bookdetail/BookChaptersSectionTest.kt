package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** Chapters that play from the chapter they name (spec §2.5): rows on a phone, a table with start times on a desktop. */
class BookChaptersSectionTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val chapters =
            (0 until 12).map { i ->
                ChapterUiModel(
                    id = "c$i",
                    title = "Chapter ${i + 1}",
                    duration = "10:00",
                    imageUrl = null,
                    isCurrent = i == 5,
                    startMs = i * 600_000L,
                    durationMs = 600_000L,
                )
            }

        fun mount(
            wide: Boolean,
            canPlay: Boolean = true,
            onPlay: (Long) -> Unit = {},
            onOpenMap: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookChaptersSection(
                    bookId = "42",
                    chapters = chapters,
                    preview = chapters.subList(3, 8),
                    canPlay = canPlay,
                    wide = wide,
                    onPlayFromChapter = onPlay,
                    onOpenChapterMap = onOpenMap,
                )
            }

        fun playButtons(root: HTMLElement) = root.querySelectorAll("button[aria-label^='Play from chapter']").asList()

        test("each row is a button named Play from chapter N, and a click plays from its start") {
            var started: Long? = null
            val root = mount(wide = false, onPlay = { started = it })
            (root.querySelector("button[aria-label='Play from chapter 7: Chapter 7']") as HTMLButtonElement).click()
            started shouldBe 3_600_000L
        }

        test("the collapsed list is the five-row window around the current chapter") {
            val root = mount(wide = false)
            playButtons(root).size shouldBe 5
            (root.querySelector("button[aria-label='Play from chapter 3: Chapter 3']") == null) shouldBe true
            (root.querySelector("button[aria-label='Play from chapter 8: Chapter 8']") != null) shouldBe true
        }

        test("the current row carries aria-current, and only it") {
            val root = mount(wide = false)
            root.querySelector("button[aria-label='Play from chapter 6: Chapter 6']")!!.getAttribute("aria-current") shouldBe "true"
            root.querySelectorAll("[aria-current]").length shouldBe 1
        }

        test("desktop is a table with a Starts column, the title cell a play button") {
            val root = mount(wide = true)
            val heads = root.querySelectorAll("table th")
            (0 until heads.length).map { heads.item(it)!!.textContent } shouldBe listOf("#", "Title", "Starts", "Length")
            // Chapter 7 starts an hour in.
            val row = root.querySelector("button[aria-label='Play from chapter 7: Chapter 7']")!!.closest("tr")!!
            row.textContent.orEmpty().contains("1:00:00") shouldBe true
        }

        test("Show all expands in place, says so, and Show fewer collapses it again") {
            val root = mount(wide = false)
            val toggle = root.querySelector(".bd-ch-all") as HTMLButtonElement
            toggle.getAttribute("aria-expanded") shouldBe "false"
            toggle.textContent shouldBe "Show all 12 chapters"
            toggle.click()
            awaitFrame()
            playButtons(root).size shouldBe 12
            val expanded = root.querySelector(".bd-ch-all") as HTMLButtonElement
            expanded.getAttribute("aria-expanded") shouldBe "true"
            expanded.textContent shouldBe "Show fewer chapters"
            expanded.click()
            awaitFrame()
            playButtons(root).size shouldBe 5
            (root.querySelector(".bd-ch-all") as HTMLButtonElement).getAttribute("aria-expanded") shouldBe "false"
        }

        test("on a desktop the play mark leads the title, as the board draws it") {
            val root = mount(wide = true)
            val button = root.querySelector("button[aria-label='Play from chapter 7: Chapter 7']") as HTMLElement
            val mark = button.firstElementChild!!
            mark.classList.contains("bd-ch-play") shouldBe true
            val title = button.querySelector(".bd-ch-t")!!
            (title.getBoundingClientRect().left - mark.getBoundingClientRect().right < 16.0) shouldBe true
        }

        test("when the book can't play the rows are disabled, and only then carry the attribute") {
            var started: Long? = null
            val off = mount(wide = false, canPlay = false, onPlay = { started = it })
            val row = off.querySelector("button[aria-label^='Play from chapter']") as HTMLButtonElement
            row.hasAttribute("disabled") shouldBe true
            row.click()
            started shouldBe null
            mount(wide = false, canPlay = true).querySelector("button[aria-label^='Play from chapter']")!!.hasAttribute("disabled") shouldBe
                false
        }

        test("the header links to the chapter map") {
            var opened = 0
            val root = mount(wide = true, onOpenMap = { opened++ })
            val link = root.querySelector("a.bd-chmap-link") as HTMLElement
            link.textContent shouldBe "Chapter map"
            link.getAttribute("href") shouldBe "/book/42/map"
            link.click()
            opened shouldBe 1
        }
    })
