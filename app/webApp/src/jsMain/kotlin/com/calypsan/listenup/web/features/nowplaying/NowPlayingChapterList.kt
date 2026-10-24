package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.player.ChapterListFollow
import com.calypsan.listenup.client.presentation.player.ChapterListMove
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.web.design.NodeHolder
import com.calypsan.listenup.web.nav.awaitAnimationFrame
import kotlinx.browser.window
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The page's chapter list (§3.11): a "Chapters" heading over how many and how long, then number · title · duration, the
 * current row filled and saying "Playing · 8m left" after a bar of how far through the chapter the playhead is.
 *
 * It opens centred on the current chapter and follows the playhead by the shared [ChapterListFollow] rule: a chapter
 * change re-centres only when the previous current row was fully in view, so a reader who scrolled away is never
 * yanked back; "Back to current chapter" is offered while the current row is out of view.
 *
 * The heading carries [CHAPTERS_HEADING_ID], so the page's region can be named by it; neither it nor the decorative
 * bar is a tab stop, so the page's focus order is the rows' alone.
 *
 * ⛔ Centred from an effect after a frame, never from a `ref` (§9.2): a `ref` runs while the container may not be laid
 * out, and the scroll landed on the wrong row.
 */
@Composable
internal fun NowPlayingChapterList(
    rows: List<PlayerChapterRow>,
    currentIndex: Int,
    chapterRemainingMs: Long,
    chapterFraction: Float,
    bookDurationMs: Long,
    onPick: (Int) -> Unit,
) {
    if (rows.isEmpty()) return
    val scroller = remember { NodeHolder() }
    var showsBack by remember { mutableStateOf(false) }
    var lastCurrent by remember { mutableStateOf<Int?>(null) }
    val current by rememberUpdatedState(currentIndex)

    LaunchedEffect(currentIndex) {
        awaitAnimationFrame()
        val list = scroller.element ?: return@LaunchedEffect
        val previous = lastCurrent
        val move =
            if (previous == null) {
                ChapterListMove.Recentre
            } else {
                ChapterListFollow.onCurrentChanged(previousRowFullyVisible = list.rowFullyVisible(previous))
            }
        if (move == ChapterListMove.Recentre) list.centre(currentIndex, smooth = previous != null)
        lastCurrent = currentIndex
        showsBack = ChapterListFollow.showsBackToCurrent(list.rowFullyVisible(currentIndex))
    }

    Div(attrs = { classes("np-listwrap") }) {
        Div(attrs = { classes("np-listhead") }) {
            H2(attrs = { id(CHAPTERS_HEADING_ID) }) { Text(PlayerSheetWords.CHAPTERS) }
            Span(
                attrs = { classes("np-listsum") },
            ) { Text(PlayerSheetWords.chaptersSummary(rows.size, bookDurationMs)) }
        }
        Div(attrs = {
            classes("np-list")
            // On the element itself: an effect beside it runs before its `ref`, so it never saw the scroller and a
            // reader who scrolled away was never offered the way back.
            onScroll {
                scroller.element?.let { showsBack = ChapterListFollow.showsBackToCurrent(it.rowFullyVisible(current)) }
            }
            ref { element ->
                scroller.element = element
                onDispose { scroller.element = null }
            }
        }) {
            Ol(attrs = { classes("np-rows") }) {
                rows.forEach { row ->
                    key(row.index) {
                        ChapterRow(row, row.index == currentIndex, chapterRemainingMs, chapterFraction, onPick)
                    }
                }
            }
        }
        if (showsBack) {
            Button(attrs = {
                classes("np-back-current")
                attr("type", "button")
                onClick { scroller.element?.centre(currentIndex, smooth = true) }
            }) { Text(NowPlayingWords.BACK_TO_CURRENT) }
        }
    }
}

@Composable
private fun ChapterRow(
    row: PlayerChapterRow,
    isCurrent: Boolean,
    chapterRemainingMs: Long,
    chapterFraction: Float,
    onPick: (Int) -> Unit,
) {
    Li {
        Button(attrs = {
            classes("np-row")
            if (isCurrent) {
                classes("on")
                attr("aria-current", "true")
            }
            attr("type", "button")
            attr("data-n", row.number.toString())
            attr("aria-label", PlayerSheetWords.chapterRowLabel(row, isCurrent, chapterRemainingMs))
            onClick { onPick(row.index) }
        }) {
            Span(attrs = { classes("np-row-n") }) { Text(row.number.toString()) }
            Span(attrs = { classes("np-row-main") }) {
                Span(attrs = { classes("np-row-t") }) { Text(row.title) }
                if (isCurrent) {
                    Span(attrs = { classes("np-row-now") }) {
                        ChapterBar(chapterFraction)
                        Text(NowPlayingWords.playingLeft(chapterRemainingMs))
                    }
                }
            }
            Span(attrs = { classes("np-row-d") }) {
                Text(DurationFormatter.minutesSecondsClock(row.durationMs.milliseconds))
            }
        }
    }
}

/**
 * How far through the chapter the playhead is, as the tablet's panel draws it (§5.3): an 80px track in the pressed coral
 * with a white fill, which reads at 3:1 against both its track and the row (C7). Hidden from assistive tech: the row's
 * name already says the time left, so the bar adds no stop and no name. A tick only restyles the fill's width.
 */
@Composable
private fun ChapterBar(fraction: Float) {
    Span(attrs = {
        classes("np-row-bar")
        attr("aria-hidden", "true")
    }) {
        Span(attrs = {
            classes("np-row-fill")
            style { width((fraction.coerceIn(0f, 1f) * PERCENT).roundToInt().percent) }
        })
    }
}

/** The row button at [index], or null. */
private fun HTMLElement.rowAt(index: Int): HTMLElement? = querySelectorAll(".np-row").item(index) as? HTMLElement

/** Whether row [index] lies wholly inside this scroller's visible box. */
private fun HTMLElement.rowFullyVisible(index: Int): Boolean {
    val row = rowAt(index) ?: return false
    val box = getBoundingClientRect()
    val rect = row.getBoundingClientRect()
    return rect.top >= box.top - EDGE_PX && rect.bottom <= box.bottom + EDGE_PX
}

/**
 * Centres row [index] in this scroller, moving only the scroller (never the page). [smooth] glides, unless the reader
 * asked for reduced motion; the first centring on open jumps.
 */
private fun HTMLElement.centre(
    index: Int,
    smooth: Boolean,
) {
    val row = rowAt(index) ?: return
    val offset = row.getBoundingClientRect().top - getBoundingClientRect().top + scrollTop
    val target = offset - (clientHeight - row.offsetHeight) / 2.0
    val glides = smooth && !window.matchMedia(REDUCED_MOTION).matches
    val options = js("{}")
    options.top = target.coerceAtLeast(0.0)
    options.behavior = if (glides) "smooth" else "auto"
    asDynamic().scrollTo(options)
}

/** The id of the list's "Chapters" heading, which names the page's chapter region (`aria-labelledby`). */
internal const val CHAPTERS_HEADING_ID = "np-chapters-h"

private const val PERCENT = 100

private const val REDUCED_MOTION = "(prefers-reduced-motion: reduce)"

/** Sub-pixel layout rounding is not "out of view". */
private const val EDGE_PX = 0.5
