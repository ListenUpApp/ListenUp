package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import org.jetbrains.compose.web.dom.Button
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

/**
 * Jump to a chapter without leaving what you are listening to.
 *
 * Book Detail already lists chapters, and this is deliberately not that list. `ChaptersPane` is an
 * editing workbench — a proportional chapter map, a multi-select table, an inspector — and none of
 * that is what someone reaching for chapter 12 mid-book wants. Borrowing it would also tie the
 * player to a surface it otherwise has no reason to know about. A name and somewhere to land is the
 * whole requirement. The rows, and which one is current, are the shared model's.
 *
 * The shell — a bottom sheet from the phone page, a popover from the bar — comes from [PlayerPanel],
 * shared with every other player panel.
 */
@Composable
internal fun ChapterPicker(
    open: Boolean,
    chapters: List<PlayerChapterRow>,
    /** Content time left in the current chapter: its row's "Playing · 8m left". */
    chapterRemainingMs: Long,
    /** The whole book's length, for the header's "75 chapters · 45h 30m". */
    bookDurationMs: Long,
    placement: PanelPlacement,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerPanel(
        open = open,
        title = NowPlayingWords.CHAPTERS,
        panelClass = "chap-dlg",
        placement = placement,
        onDismiss = onDismiss,
    ) {
        val current = remember { RowNode() }
        P(attrs = { classes("chap-sum") }) { Text(NowPlayingWords.chaptersSummary(chapters.size, bookDurationMs)) }
        Div(attrs = { classes("chap-list") }) {
            chapters.forEach { chapter ->
                key(chapter.startMs) {
                    ChapterRow(chapter, chapterRemainingMs, current, onPick)
                }
            }
        }
        // Centred on the chapter being listened to (§3.11, §5.5.4) only once the panel is open and laid out: two
        // frames after it is shown, never from the row's own `ref`, which fired while the `<dialog>` was still closed
        // and left the list at chapter 1 (§9.2, hypothesis 2). `behavior: "auto"`, so nothing animates on open.
        DisposableEffect(Unit) {
            var live = true
            window.requestAnimationFrame {
                window.requestAnimationFrame {
                    if (live) current.element?.scrollIntoView(scrollToCentre())
                }
            }
            onDispose { live = false }
        }
    }
}

/**
 * One chapter: its number, its title (with "Playing · 8m left" under the current one), and its **duration** (gap 16),
 * named for what pressing it does (§8).
 */
@Composable
private fun ChapterRow(
    chapter: PlayerChapterRow,
    chapterRemainingMs: Long,
    current: RowNode,
    onPick: (Int) -> Unit,
) {
    val isCurrent = chapter.isCurrent
    Button(attrs = {
        classes("chap-row")
        if (isCurrent) classes("on")
        attr("type", "button")
        // `aria-current`, not just a class: the highlight is information, and a class alone says nothing to a reader
        // who cannot see it.
        if (isCurrent) attr("aria-current", "true")
        attr("aria-label", NowPlayingWords.chapterRowLabel(chapter, isCurrent, chapterRemainingMs))
        onClick { onPick(chapter.index) }
        if (isCurrent) {
            ref { element ->
                current.element = element
                onDispose { if (current.element == element) current.element = null }
            }
        }
    }) {
        Span(attrs = { classes("chap-n") }) { Text("${chapter.number}") }
        Span(attrs = { classes("chap-tx") }) {
            Span(attrs = { classes("chap-t") }) { Text(chapter.title) }
            if (isCurrent) Span(attrs = { classes("chap-now") }) { Text(NowPlayingWords.playingLeft(chapterRemainingMs)) }
        }
        Span(attrs = { classes("chap-dur") }) { Text(formatElapsed(chapter.durationMs)) }
    }
}

/** The current row's element, captured by `ref`, for the centring that runs once the panel is open. */
private class RowNode {
    var element: HTMLElement? = null
}

/**
 * `scrollIntoView` options as a JS object.
 *
 * `block: "center"` rather than the default `"start"`: a chapter pinned to the top edge hides the
 * ones before it, and the listener's sense of where they are in the book comes from seeing both
 * sides. `behavior: "auto"` because this runs as the panel opens — an animated scroll from the top
 * of a long list is motion the listener did not ask for and has to wait out.
 */
private fun scrollToCentre(): dynamic {
    val options = js("{}")
    options.block = "center"
    options.behavior = "auto"
    return options
}
