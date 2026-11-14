package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.groupedChapters
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTableCellElement
import org.w3c.dom.asList

private const val SPAN_MS = 600_000L

/**
 * A marked book on the web: every chapter is a row, and a part is a divider above its chapters. The fixtures
 * run the real outline here, in the browser: sharedLogic has no JS lane, and Kotlin/JS has already disagreed
 * with the JVM once about this feature.
 *
 * Credits, then Part I with its own "Part I" chapter and 1 to 6, then Part II with "Part II" and 7 to 9.
 */
class BookChaptersGroupedTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val book =
            listOf("Credits", "Part I", "1", "2", "3", "4", "5", "6", "Part II", "7", "8", "9").mapIndexed { i, title ->
                Chapter(
                    id = "c$i",
                    title = title,
                    duration = SPAN_MS,
                    startTime = i * SPAN_MS,
                    partTitle = title.takeIf { it.startsWith("Part") },
                )
            }

        fun rowsOf(
            chapters: List<Chapter>,
            currentIndex: Int?,
        ) = chapters.mapIndexed { i, chapter ->
            ChapterUiModel(
                id = chapter.id,
                title = chapter.title,
                duration = "10:00",
                imageUrl = null,
                isCurrent = i == currentIndex,
                startMs = chapter.startTime,
                durationMs = chapter.duration,
            )
        }

        fun mount(
            currentIndex: Int? = 6,
            canPlay: Boolean = true,
            wide: Boolean = false,
            chapters: List<Chapter> = book,
            onPlay: (Long) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                val rows = rowsOf(chapters, currentIndex)
                val grouped = chapters.groupedChapters(currentIndex)
                // The page's preview is the same five chapters the outline's window holds.
                val windowIds =
                    grouped
                        ?.previewRows
                        ?.filterIsInstance<ChapterOutlineRow.ChapterRow>()
                        ?.map { it.chapter.id }
                        .orEmpty()
                BookChaptersSection(
                    bookId = "42",
                    chapters = rows,
                    preview = rows.filter { it.id in windowIds },
                    canPlay = canPlay,
                    wide = wide,
                    onPlayFromChapter = onPlay,
                    onOpenChapterMap = {},
                    grouped = grouped,
                )
            }

        fun chapterRow(
            root: HTMLElement,
            label: String,
        ) = root.querySelector("button[aria-label='Play from chapter $label']") as HTMLButtonElement?

        fun chapterRows(root: HTMLElement) =
            root.querySelectorAll("button[aria-label^='Play from chapter']").asList().map {
                (it as Element).getAttribute("aria-label").orEmpty().removePrefix("Play from chapter ")
            }

        fun dividers(root: HTMLElement) = root.querySelectorAll(".bd-part").asList().map { it as HTMLElement }

        fun divider(
            root: HTMLElement,
            title: String,
        ) = dividers(root).firstOrNull { it.querySelector(".bd-part-t")?.textContent == title }

        suspend fun showAll(root: HTMLElement) {
            (root.querySelector(".bd-ch-all") as HTMLButtonElement).click()
            awaitFrame()
        }

        test("a flat book is not grouped in the browser either") {
            book.map { it.copy(partTitle = null) }.groupedChapters(currentIndex = 2).shouldBeNull()
        }

        test("every chapter the audiobook has is a row, numbered in audio order, the part's own chapter too") {
            val root = mount(currentIndex = 6)
            showAll(root)

            chapterRows(root) shouldContainExactly
                listOf(
                    "1: Credits",
                    "2: Part I",
                    "3: 1",
                    "4: 2",
                    "5: 3",
                    "6: 4",
                    "7: 5",
                    "8: 6",
                    "9: Part II",
                    "10: 7",
                    "11: 8",
                    "12: 9",
                )
        }

        test("a part is a heading above its chapters, not a button, and nothing opens or closes") {
            val root = mount(currentIndex = 6)
            showAll(root)

            val part = divider(root, "Part II").shouldNotBeNull()
            part.tagName shouldBe "H3"
            part.querySelector("button").shouldBeNull()
            part.hasAttribute("tabindex") shouldBe false
            // The only control in the list that expands anything is "Show all".
            root.querySelectorAll("[aria-expanded]").length shouldBe 1
            // The part's opening chapter is the row straight after its divider.
            part.nextElementSibling?.getAttribute("aria-label") shouldBe "Play from chapter 9: Part II"
        }

        test("a divider says how many chapters the part has and how long it runs, spelled out for a screen reader") {
            val root = mount(currentIndex = 6)
            showAll(root)

            val partOne = divider(root, "Part I").shouldNotBeNull()
            partOne.querySelector(".bd-part-sub")?.textContent shouldBe "7 chapters · 1h 10m"
            partOne.getAttribute("aria-label") shouldBe "Part I, 7 chapters, 1 hour 10 minutes"
            divider(root, "Part II").shouldNotBeNull().getAttribute("aria-label") shouldBe
                "Part II, 4 chapters, 40 minutes"
        }

        test("only the row is current: the divider above it never is") {
            val root = mount(currentIndex = 8)
            showAll(root)

            chapterRow(root, "9: Part II").shouldNotBeNull().getAttribute("aria-current") shouldBe "true"
            root.querySelectorAll("[aria-current]").length shouldBe 1
            divider(root, "Part II").shouldNotBeNull().classList.contains("is-current") shouldBe false
        }

        test("the collapsed window shows five rows, with a divider wherever it crosses into a part") {
            val root = mount(currentIndex = 7)

            chapterRows(root) shouldContainExactly listOf("6: 4", "7: 5", "8: 6", "9: Part II", "10: 7")
            dividers(root).map { it.querySelector(".bd-part-t")?.textContent } shouldContainExactly
                listOf("Part I", "Part II")
        }

        test("a divider reads as a clear break: space above and a rule, except at the top of the card") {
            for (wide in listOf(false, true)) {
                val root = mount(currentIndex = 7, wide = wide)
                val (first, second) = dividers(root)

                window.getComputedStyle(first).borderTopWidth shouldBe "0px"
                window.getComputedStyle(first).marginTop shouldBe "0px"
                window.getComputedStyle(second).borderTopWidth shouldBe "2px"
                window.getComputedStyle(second).marginTop shouldBe "28px"
                // The title is a heading, set apart from the rows' type.
                window.getComputedStyle(second.querySelector(".bd-part-t").shouldNotBeNull()).fontWeight shouldBe
                    "800"
                mounts.disposeAll()
            }
        }

        test("a row plays from its own chapter") {
            var started: Long? = null
            val root = mount(currentIndex = 6, onPlay = { started = it })

            chapterRow(root, "7: 5").shouldNotBeNull().click()

            started shouldBe 6 * SPAN_MS
        }

        test("a book that cannot play still lists every row, each disabled") {
            val root = mount(currentIndex = null, canPlay = false)
            showAll(root)

            chapterRow(root, "10: 7").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("an omnibus heads its parts with the book, a level above them, and numbers each book from 1") {
            val omnibus = book.mapIndexed { i, chapter -> if (i == 1) chapter.copy(bookTitle = "Book One") else chapter }
            var started: Long? = null
            val root = mount(currentIndex = 6, chapters = omnibus, onPlay = { started = it })
            showAll(root)

            val header = root.querySelector(".bd-book").shouldNotBeNull()
            header.tagName shouldBe "H3"
            header.textContent shouldBe "Book One"
            header.querySelector("button").shouldBeNull()
            divider(root, "Part I").shouldNotBeNull().tagName shouldBe "H4"
            // Numbering starts again at the book; the row still plays its own chapter, not the number's.
            chapterRow(root, "1: Part I").shouldNotBeNull()
            chapterRow(root, "2: 1").shouldNotBeNull().click()
            started shouldBe 2 * SPAN_MS
        }

        test("a wide page keeps its table, with each divider spanning every column") {
            val root = mount(currentIndex = 6, wide = true)
            showAll(root)

            root.querySelectorAll(".bd-ch-table").length shouldBe 1
            val cell = divider(root, "Part II").shouldNotBeNull().closest("td") as HTMLTableCellElement
            cell.colSpan shouldBe 4
            chapterRow(root, "9: Part II").shouldNotBeNull().closest("tr") shouldBe
                cell.closest("tr")?.nextElementSibling
        }

        test("on a wide page the columns line up on both sides of a divider") {
            val root = mount(currentIndex = 6, wide = true)
            showAll(root)

            val starts =
                root
                    .querySelectorAll(".bd-ch-table tbody tr:not(.bd-part-tr) td:nth-child(3)")
                    .asList()
                    .map { (it as HTMLElement).getBoundingClientRect().left }
            starts.size shouldBe book.size
            starts.distinct().size shouldBe 1
        }
    })
