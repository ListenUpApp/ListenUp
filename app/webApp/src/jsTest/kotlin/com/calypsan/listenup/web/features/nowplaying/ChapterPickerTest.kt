package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit
import kotlin.math.abs

/** Ten minutes. Long enough that a formatted start time is unambiguous in an assertion. */
private const val CHAPTER_LENGTH_MS = 600_000L

/** [count] ten-minute chapter rows, with [currentIndex] marked current (the shared model's rows). */
internal fun chapterMarks(
    count: Int = 3,
    currentIndex: Int? = 0,
): List<PlayerChapterRow> =
    (0 until count).map {
        PlayerChapterRow(
            index = it,
            number = it + 1,
            title = "Chapter ${it + 1}",
            startMs = it * CHAPTER_LENGTH_MS,
            durationMs = CHAPTER_LENGTH_MS,
            isCurrent = it == currentIndex,
        )
    }

class ChapterPickerTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        /**
         * An open picker over [chapterMarks], with every parameter overridable.
         *
         * One shape for every case in this spec: a test that differs from its neighbour in one
         * argument says what it is about in that argument, rather than in six lines of repeated
         * wiring.
         */
        fun picker(
            currentIndex: Int? = 0,
            chapters: List<PlayerChapterRow> = chapterMarks(currentIndex = currentIndex),
            open: Boolean = true,
            onPick: (Int) -> Unit = {},
            onDismiss: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    ChapterPicker(
                        open = open,
                        chapters = chapters,
                        chapterRemainingMs = 480_000,
                        bookDurationMs = 163_800_000,
                        onPick = onPick,
                        placement = PanelPlacement.Sheet,
                        onDismiss = onDismiss,
                    )
                }
            }

        test("a closed picker renders nothing at all") {
            val host = picker(open = false)

            host.querySelectorAll("dialog").length shouldBe 0
        }

        test("an open picker lists every chapter with where it starts") {
            val host = picker()

            host.querySelectorAll(".chap-row").length shouldBe 3
            val text = host.textContent.orEmpty()
            text shouldContain "Chapter 2"
            // 600_000 ms in — the listener needs somewhere to land, not just a name.
            text shouldContain "10:00"

            host.closeDialogs()
        }

        test("picking a chapter reports its index") {
            var picked: Int? = null
            val host = picker(onPick = { picked = it })

            (host.querySelectorAll(".chap-row").item(2) as HTMLElement).click()

            picked shouldBe 2
            host.closeDialogs()
        }

        test("the chapter being listened to is marked, and says so to a screen reader") {
            // `aria-current` rather than a class alone: the highlight is information, and a class
            // says nothing to a reader who cannot see it.
            val host = picker(currentIndex = 1)

            val rows = host.querySelectorAll(".chap-row")
            (rows.item(0) as HTMLElement).getAttribute("aria-current") shouldBe null
            (rows.item(1) as HTMLElement).getAttribute("aria-current") shouldBe "true"
            host.querySelectorAll(".chap-row.on").length shouldBe 1

            host.closeDialogs()
        }

        test("no current chapter marks nothing rather than defaulting to the first") {
            // A book with no playhead in it yet must not claim the listener is in chapter one.
            val host = picker(currentIndex = null)

            host.querySelectorAll(".chap-row.on").length shouldBe 0

            host.closeDialogs()
        }

        test("rows show the chapter's duration, not where it starts") {
            val rows =
                listOf(
                    PlayerChapterRow(5, 6, "Bridge Four", 0, 1_235_000, isCurrent = true),
                    PlayerChapterRow(6, 7, "Anything Reasonable", 1_235_000, 1_998_000, isCurrent = false),
                )
            val host = picker(chapters = rows)

            val seventh = host.querySelectorAll(".chap-row").item(1) as HTMLElement
            seventh.textContent.orEmpty().endsWith("33:18") shouldBe true

            host.closeDialogs()
        }

        test("the current row shows 'Playing · 8m left' and is named '8 minutes left', and every row for what pressing it does") {
            val rows =
                listOf(
                    PlayerChapterRow(5, 6, "Bridge Four", 0, 1_235_000, isCurrent = true),
                    PlayerChapterRow(6, 7, "Anything Reasonable", 1_235_000, 1_998_000, isCurrent = false),
                )
            val host = picker(chapters = rows)
            val current = host.querySelectorAll(".chap-row").item(0) as HTMLElement
            val next = host.querySelectorAll(".chap-row").item(1) as HTMLElement

            current.textContent.orEmpty() shouldContain "Playing · 8m left"
            current.getAttribute("aria-label") shouldBe "Chapter 6, Bridge Four, now playing, 8 minutes left"
            next.getAttribute("aria-label") shouldBe "Play from chapter 7: Anything Reasonable, 33:18"

            host.closeDialogs()
        }

        test("the header sums the book") {
            val host = picker(chapters = chapterMarks(count = 75))

            host.querySelector(".chap-sum")?.textContent shouldBe "75 chapters · 45h 30m"

            host.closeDialogs()
        }

        test("§9.2: opening at chapter 39 of 68 centres row 39, marks it alone, and a pointer over row 10 marks nothing") {
            var open by mutableStateOf(false)
            val host =
                mounts.mount {
                    WebAppSurface {
                        ChapterPicker(
                            open = open,
                            chapters = chapterMarks(count = 68, currentIndex = 38),
                            chapterRemainingMs = 480_000,
                            bookDurationMs = 68 * CHAPTER_LENGTH_MS,
                            placement = PanelPlacement.Sheet,
                            onPick = {},
                            onDismiss = { open = false },
                        )
                    }
                }
            // The real order of events: the panel exists closed, then opens.
            awaitFrame()
            open = true
            awaitFrame()
            awaitFrame()

            val list = host.querySelector(".chap-list") as HTMLElement
            val rows = host.querySelectorAll(".chap-row").asList().map { it as HTMLElement }
            val row39 = rows[38]
            val listBox = list.getBoundingClientRect()
            val rowBox = row39.getBoundingClientRect()
            val rowCentre = rowBox.top + rowBox.height / 2
            val listCentre = listBox.top + listBox.height / 2
            abs(rowCentre - listCentre) shouldBeLessThan rowBox.height
            rows.filter { it.getAttribute("aria-current") == "true" } shouldBe listOf(row39)
            rows.filter { it.classList.contains("on") } shouldBe listOf(row39)

            // Hypothesis 1: a row tinted under a resting pointer is :hover, never `.on`.
            rows[9].dispatchEvent(PointerEvent("pointermove", PointerEventInit(bubbles = true)))
            awaitFrame()
            rows[9].classList.contains("on") shouldBe false
            rows.filter { it.classList.contains("on") } shouldBe listOf(row39)

            host.closeDialogs()
        }

        test("it is a real modal dialog, not a div wearing the part") {
            // `showModal()` is what buys the focus trap, the inert page behind and Escape-to-close.
            // A `div` with role=dialog looks identical and provides none of it.
            val host = picker()

            val dialog = host.querySelector("dialog") as HTMLDialogElement
            dialog.isModal() shouldBe true

            host.closeDialogs()
        }

        test("Escape's close request dismisses the panel") {
            // `requestClose()` is the path Escape takes on a modal dialog: cancel, then close. A
            // synthetic Escape keydown would not exercise it at all.
            var dismissed = 0
            val host = picker(onDismiss = { dismissed++ })

            (host.querySelector("dialog") as HTMLDialogElement).asDynamic().requestClose()
            awaitFrame()

            dismissed shouldNotBe 0
            host.closeDialogs()
        }

        test("it wears the app's one modal shell, labelled by its own heading") {
            val host = picker()
            val dialog = host.querySelector("dialog.dlg.chap-dlg") as HTMLDialogElement

            val heading = dialog.querySelector(".dlg-head > .dlg-t") as HTMLElement
            dialog.getAttribute("aria-labelledby") shouldBe heading.id

            host.closeDialogs()
        }

        test("closing reports the dismissal, so the caller's flag cannot drift") {
            var dismissed = 0
            val host = picker(onDismiss = { dismissed++ })

            (host.querySelector(".btn-secondary") as HTMLButtonElement).click()
            awaitFrame()

            dismissed shouldNotBe 0
            host.closeDialogs()
        }
    })
