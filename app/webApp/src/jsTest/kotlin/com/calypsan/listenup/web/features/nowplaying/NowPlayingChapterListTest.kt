package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * The page's chapter list (§3.11, §9.2): it opens centred on the current chapter, after layout; exactly one row is
 * current; it follows the playhead only while the old current row was in view, and otherwise offers "Back to current
 * chapter". Measured in a real 1440×900 frame.
 */
class NowPlayingChapterListTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun listWithState(
            rows: List<PlayerChapterRow>,
            current: MutableState<Int>,
            chapterRemainingMs: Long = 0,
            chapterFraction: MutableState<Float> = mutableStateOf(0f),
            onPick: (Int) -> Unit = {},
        ): ViewportFrame =
            frames.mount(width = DESKTOP_WIDTH, height = DESKTOP_HEIGHT) {
                NowPlayingChapterList(
                    rows = rows.map { it.copy(isCurrent = it.index == current.value) },
                    currentIndex = current.value,
                    chapterRemainingMs = chapterRemainingMs,
                    chapterFraction = chapterFraction.value,
                    bookDurationMs = KINGS_MS,
                    onPick = onPick,
                )
            }

        fun list(
            rows: List<PlayerChapterRow>,
            current: Int,
            chapterRemainingMs: Long = 0,
            chapterFraction: Float = 0f,
            onPick: (Int) -> Unit = {},
        ): ViewportFrame = listWithState(rows, mutableStateOf(current), chapterRemainingMs, mutableStateOf(chapterFraction), onPick)

        fun ViewportFrame.row(index: Int): HTMLElement = findAll(".np-row")[index]

        fun ViewportFrame.scroller(): HTMLElement = find(".np-list")

        test("§9.2: opening at chapter 39 of 68 puts row 39 in view, aria-current on row 39 only, and no other row is .on") {
            val frame = list(chapterRows(68, 60_000), current = 38)
            awaitFrame()
            awaitFrame()
            val rows = frame.findAll(".np-row")
            rows.filter { it.getAttribute("aria-current") == "true" }.map { it.getAttribute("data-n") } shouldBe listOf("39")
            rows.filter { it.classList.contains("on") }.map { it.getAttribute("data-n") } shouldBe listOf("39")
            awaitVisible(frame.scroller(), rows[38])
        }

        test("each row reads number, title and duration; the current one says Playing and the time left in it") {
            val frame = list(chapterRows(3, 600_000), current = 1, chapterRemainingMs = 511_000)
            frame.find(".np-row.on").textContent.orEmpty() shouldContain "Playing · 8m left"
            frame.find(".np-row .np-row-d").textContent shouldBe "10:00"
        }

        test("a row's name is the chapter popover's: Play from chapter 3, and the current one now playing with its time left in words") {
            val frame = list(chapterRows(3, 600_000), current = 1, chapterRemainingMs = 511_000)
            frame.find(".np-row.on").getAttribute("aria-label") shouldBe "Chapter 2, Chapter 2, now playing, 8 minutes left"
            frame.row(2).getAttribute("aria-label") shouldBe "Play from chapter 3: Chapter 3, 10:00"
        }

        test("the list is headed Chapters, with how many and how long, and the heading is not a tab stop") {
            val frame = list(chapterRows(75, 60_000), current = 5)
            val heading = frame.find(".np-listhead h2")
            heading.textContent shouldBe "Chapters"
            heading.id shouldBe CHAPTERS_HEADING_ID
            heading.hasAttribute("tabindex") shouldBe false
            frame.find(".np-listhead .np-listsum").textContent shouldBe "75 chapters · 45h 30m"
        }

        test("the current row shows the chapter's progress as a bar, hidden from assistive tech") {
            val frame = list(chapterRows(3, 600_000), current = 1, chapterRemainingMs = 511_000, chapterFraction = 0.59f)
            frame.findAll(".np-row-bar").size shouldBe 1
            val bar = frame.find(".np-row.on .np-row-bar")
            bar.getAttribute("aria-hidden") shouldBe "true"
            frame.find(".np-row.on .np-row-fill").style.width shouldBe "59%"
        }

        test("the bar moves with the playhead without re-rendering the list") {
            val fraction = mutableStateOf(0.59f)
            val frame = listWithState(chapterRows(3, 600_000), mutableStateOf(1), 511_000, fraction)
            val fill = frame.find(".np-row.on .np-row-fill")
            val label = frame.find(".np-row.on").getAttribute("aria-label")
            fraction.value = 0.60f
            awaitFrame()
            frame.find(".np-row.on .np-row-fill") shouldBeSameInstanceAs fill
            fill.style.width shouldBe "60%"
            frame.find(".np-row.on").getAttribute("aria-label") shouldBe label
        }

        test("the bar sits before Playing · 8m left, as on the tablet") {
            val frame = list(chapterRows(3, 600_000), current = 1, chapterRemainingMs = 511_000, chapterFraction = 0.5f)
            val now = frame.find(".np-row.on .np-row-now")
            (now.firstElementChild as HTMLElement).classList.contains("np-row-bar") shouldBe true
            now.textContent shouldBe "Playing · 8m left"
        }

        test("a row is a button that plays from that chapter") {
            val picked = mutableListOf<Int>()
            val frame = list(chapterRows(3), current = 0, onPick = { picked += it })
            (frame.row(2) as HTMLButtonElement).click()
            picked shouldBe listOf(2)
        }

        test("the list follows a chapter change while the old current row was in view") {
            val current = mutableStateOf(10)
            val frame = listWithState(chapterRows(68, 60_000), current)
            awaitVisible(frame.scroller(), frame.row(10))
            current.value = 11
            awaitFrame()
            awaitVisible(frame.scroller(), frame.row(11))
            frame.host.ownerDocument!!.querySelector(".np-back-current") shouldBe null
        }

        test("scrolling the current row out of view offers Back to current chapter, with no chapter change") {
            val frame = list(chapterRows(68, 60_000), current = 10)
            val scroller = frame.scroller()
            awaitFrame()
            awaitFrame()
            awaitVisible(scroller, frame.row(10))
            frame.host.ownerDocument!!.querySelector(".np-back-current") shouldBe null
            scroller.scrollTop = scroller.scrollHeight.toDouble()
            withTimeout(SCROLL_TIMEOUT_MS) {
                while (frame.host.ownerDocument!!.querySelector(".np-back-current") == null) delay(POLL_MS)
            }
            (frame.find(".np-back-current") as HTMLButtonElement).click()
            awaitVisible(scroller, frame.row(10))
            withTimeout(SCROLL_TIMEOUT_MS) {
                while (frame.host.ownerDocument!!.querySelector(".np-back-current") != null) delay(POLL_MS)
            }
        }

        test("scrolled away, a chapter change leaves the list alone and offers Back to current chapter") {
            val current = mutableStateOf(10)
            val frame = listWithState(chapterRows(68, 60_000), current)
            val scroller = frame.scroller()
            // The open-centring runs a frame after layout (§9.2); scroll away only once it has.
            awaitFrame()
            awaitFrame()
            awaitVisible(scroller, frame.row(10))
            scroller.scrollTop = scroller.scrollHeight.toDouble()
            awaitFrame()
            val before = scroller.scrollTop
            current.value = 11
            awaitFrame()
            awaitFrame()
            scroller.scrollTop shouldBe before
            val back = frame.find(".np-back-current") as HTMLButtonElement
            back.textContent shouldBe "Back to current chapter"
            back.click()
            awaitVisible(scroller, frame.row(11))
        }
    })

/** Whether [row] is wholly inside [scroller]'s visible box. */
internal fun isFullyVisible(
    scroller: HTMLElement,
    row: HTMLElement,
): Boolean {
    val box = scroller.getBoundingClientRect()
    val r = row.getBoundingClientRect()
    return r.top >= box.top - EDGE_PX && r.bottom <= box.bottom + EDGE_PX
}

/** Waits for [row] to be fully in [scroller]'s view: a centring may glide rather than jump. */
private suspend fun awaitVisible(
    scroller: HTMLElement,
    row: HTMLElement,
) {
    withTimeout(SCROLL_TIMEOUT_MS) { while (!isFullyVisible(scroller, row)) delay(POLL_MS) }
}

private const val DESKTOP_WIDTH = 1440
private const val DESKTOP_HEIGHT = 900
private const val EDGE_PX = 0.5
private const val SCROLL_TIMEOUT_MS = 2_000L
private const val POLL_MS = 16L
