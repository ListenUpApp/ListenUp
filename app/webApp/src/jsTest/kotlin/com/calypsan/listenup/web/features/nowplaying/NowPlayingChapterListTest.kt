package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
            onPick: (Int) -> Unit = {},
        ): ViewportFrame =
            frames.mount(width = DESKTOP_WIDTH, height = DESKTOP_HEIGHT) {
                NowPlayingChapterList(
                    rows = rows.map { it.copy(isCurrent = it.index == current.value) },
                    currentIndex = current.value,
                    chapterRemainingMs = chapterRemainingMs,
                    onPick = onPick,
                )
            }

        fun list(
            rows: List<PlayerChapterRow>,
            current: Int,
            chapterRemainingMs: Long = 0,
            onPick: (Int) -> Unit = {},
        ): ViewportFrame = listWithState(rows, mutableStateOf(current), chapterRemainingMs, onPick)

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
            frame.find(".np-row.on").getAttribute("aria-label") shouldBe "Chapter 2, Chapter 2, playing, 8 minutes left"
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
