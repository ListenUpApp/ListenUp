package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.groupedChapters
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

private const val SPAN_MS = 600_000L

/**
 * A marked book on the web. The fixtures run the real outline here, in the browser: sharedLogic has
 * no JS lane, and Kotlin/JS has already disagreed with the JVM once about this feature.
 *
 * Credits, then Part I (folded) with 1 to 6, then Part II (folded) with 7 to 9.
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

        /** Where playback is, as state: a spec moves it the way the player would. */
        var playhead by mutableStateOf<Int?>(null)

        fun mount(
            currentIndex: Int? = 6,
            canPlay: Boolean = true,
            wide: Boolean = false,
            chapters: List<Chapter> = book,
            onPlay: (Long) -> Unit = {},
        ): HTMLElement {
            playhead = currentIndex
            return mounts.mount {
                val index = playhead
                val rows = rowsOf(chapters, index)
                BookChaptersSection(
                    bookId = "42",
                    chapters = rows,
                    preview = rows.take(5),
                    canPlay = canPlay,
                    wide = wide,
                    onPlayFromChapter = onPlay,
                    onOpenChapterMap = {},
                    grouped = chapters.groupedChapters(index),
                )
            }
        }

        fun playPart(
            root: HTMLElement,
            title: String,
        ) = root.querySelector("button[aria-label='Play $title']") as HTMLButtonElement?

        fun toggle(
            root: HTMLElement,
            title: String,
        ) = root.querySelector("button[aria-expanded][data-part='$title']") as HTMLButtonElement?

        fun chapterRow(
            root: HTMLElement,
            label: String,
        ) = root.querySelector("button[aria-label='Play from chapter $label']") as HTMLButtonElement?

        suspend fun showAll(root: HTMLElement) {
            (root.querySelector(".bd-ch-all") as HTMLButtonElement).click()
            awaitFrame()
        }

        test("a flat book is not grouped in the browser either") {
            book.map { it.copy(partTitle = null) }.groupedChapters(currentIndex = 2).shouldBeNull()
        }

        test("the collapsed list names the part it sits in") {
            val root = mount(currentIndex = 6)

            root.querySelector(".bd-part-label")?.textContent shouldBe "Part I"
        }

        test("the next part's header shows where it falls in the window, and plays the chapter folded into it") {
            var started: Long? = null
            val root = mount(currentIndex = 7, onPlay = { started = it })

            playPart(root, "Part II").shouldNotBeNull().click()

            started shouldBe 8 * SPAN_MS
        }

        test("the announcement is folded, in the browser: no row repeats its part") {
            val root = mount(currentIndex = 7)

            playPart(root, "Part II").shouldNotBeNull()
            root.querySelector("button[aria-label\$=': Part II']").shouldBeNull()
        }

        test("a row keeps the number it has in the whole book") {
            chapterRow(mount(currentIndex = 6), "7: 5").shouldNotBeNull()
        }

        test("All chapters opens the current part and leaves the others closed") {
            val root = mount(currentIndex = 6)
            showAll(root)

            toggle(root, "Part I").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "true"
            toggle(root, "Part II").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "false"
            chapterRow(root, "3: 1").shouldNotBeNull()
            chapterRow(root, "10: 7").shouldBeNull()
        }

        test("chapters outside any part stay in the list") {
            val root = mount(currentIndex = 6)
            showAll(root)

            chapterRow(root, "1: Credits").shouldNotBeNull()
        }

        test("a part opens and closes without playing, and its own play stays separate") {
            val plays = mutableListOf<Long>()
            val root = mount(currentIndex = 6, onPlay = { plays += it })
            showAll(root)

            toggle(root, "Part II").shouldNotBeNull().click()
            awaitFrame()
            chapterRow(root, "10: 7").shouldNotBeNull()

            toggle(root, "Part II").shouldNotBeNull().click()
            awaitFrame()
            chapterRow(root, "10: 7").shouldBeNull()
            plays shouldBe emptyList()

            playPart(root, "Part II").shouldNotBeNull().click()
            plays shouldBe listOf(8 * SPAN_MS)
            toggle(root, "Part II").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "false"
        }

        test("a part says how many chapters it lists and how long it runs, in its accessible name too") {
            val root = mount(currentIndex = 6)
            showAll(root)

            val part = toggle(root, "Part II").shouldNotBeNull()
            part.textContent.orEmpty() shouldContain "3 chapters · 40m"
            part.getAttribute("aria-label").orEmpty() shouldContain "Part II"
        }

        test("when the current chapter is the folded one, its header shows as current") {
            val root = mount(currentIndex = 8)
            showAll(root)

            playPart(root, "Part II").shouldNotBeNull().getAttribute("aria-current") shouldBe "true"
            playPart(root, "Part I").shouldNotBeNull().hasAttribute("aria-current") shouldBe false
        }

        test("a book that cannot play can still open its parts, and plays nothing") {
            val root = mount(currentIndex = null, canPlay = false)
            showAll(root)

            playPart(root, "Part II").shouldNotBeNull().hasAttribute("disabled") shouldBe true
            toggle(root, "Part II").shouldNotBeNull().click()
            awaitFrame()
            chapterRow(root, "10: 7").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("a part the reader opened stays open when playback moves into it") {
            val root = mount(currentIndex = 6)
            showAll(root)
            toggle(root, "Part II").shouldNotBeNull().click()
            awaitFrame()

            // What pressing a chapter inside the part does: the playhead lands in it.
            playhead = 9
            awaitFrame()

            toggle(root, "Part II").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "true"
            chapterRow(root, "10: 7").shouldNotBeNull()
        }

        test("a part the reader closed stays closed when playback moves on") {
            val root = mount(currentIndex = 6)
            showAll(root)
            toggle(root, "Part I").shouldNotBeNull().click()
            awaitFrame()

            playhead = 9
            awaitFrame()

            toggle(root, "Part I").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "false"
            chapterRow(root, "3: 1").shouldBeNull()
        }

        test("a part nobody touched follows the playhead") {
            val root = mount(currentIndex = 6)
            showAll(root)

            playhead = 9
            awaitFrame()

            toggle(root, "Part I").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "false"
            toggle(root, "Part II").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "true"
        }

        test("a part's name uses the app's type, not the browser's button font") {
            val root = mount(currentIndex = 6)
            showAll(root)

            val part = toggle(root, "Part I").shouldNotBeNull()

            window.getComputedStyle(part).fontFamily shouldBe
                window.getComputedStyle(part.parentElement.shouldNotBeNull()).fontFamily
        }

        test("a book header is an outer tier that closes nothing") {
            val omnibus = book.mapIndexed { i, chapter -> if (i == 1) chapter.copy(bookTitle = "Book One") else chapter }
            val root = mount(currentIndex = 6, chapters = omnibus)
            showAll(root)

            val header = root.querySelector(".bd-book").shouldNotBeNull()
            header.textContent shouldBe "Book One"
            header.querySelector("button").shouldBeNull()
            // Everything inside it is still drawn: its parts, and the open one's chapters.
            toggle(root, "Part I").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "true"
            toggle(root, "Part II").shouldNotBeNull().getAttribute("aria-expanded") shouldBe "false"
            chapterRow(root, "3: 1").shouldNotBeNull()
        }

        test("a wide page draws the outline too, rather than its flat table") {
            val root = mount(currentIndex = 6, wide = true)

            root.querySelector(".bd-part-label")?.textContent shouldBe "Part I"
            root.querySelector(".bd-ch-table").shouldBeNull()
        }
    })
