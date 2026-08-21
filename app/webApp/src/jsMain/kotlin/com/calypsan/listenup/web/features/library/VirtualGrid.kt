package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookListItem
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.events.Event

/**
 * One laid-out row of the library grid. A [Header] spans the full width and so always starts a new
 * row; a [Books] row holds exactly one grid row's worth of cards.
 */
private sealed interface GridRow {
    data class Header(
        val letter: Char,
    ) : GridRow

    data class Books(
        val items: List<BookListItem>,
    ) : GridRow
}

/** What the grid needs to know to turn a scroll offset into a range of rows. */
private data class Metrics(
    val columns: Int,
    val rowHeight: Double,
    val headerHeight: Double,
) {
    val known: Boolean get() = columns > 0 && rowHeight > 0.0
}

/**
 * The library grid, rendering only the rows near the viewport.
 *
 * ⛔ **Why this exists.** The unvirtualised grid put all 1,204 books in the DOM. Measured against
 * the real library, that cost **2,525 ms** to re-sort and **4,064 ms** to navigate away from —
 * and the DOM was not the culprit: detaching and reattaching all 4,841 nodes by hand took **68 ms**
 * combined. The seconds were Compose, diffing and recomposing 1,204 composables. So the fix has to
 * reduce the number of *composables*, which is what this does; CSS containment
 * (`content-visibility`) would have skipped browser layout the browser was not spending time on.
 *
 * Row offsets are arithmetic, not measurement, which is only possible because every card is exactly
 * the same height — see the `.lib-title` / `.lib-author` clamps in the stylesheet. A grid whose row
 * heights depended on their contents would need each row measured after render, which is a
 * different and much slower design.
 *
 * [Metrics] are measured from the first render rather than derived from the stylesheet's numbers: a
 * responsive `auto-fill` grid changes its column width with the window, and a Kotlin copy of the
 * CSS's sizes would be a second source of truth that drifts the first time someone edits the sheet.
 */
@Composable
internal fun VirtualBookGrid(
    books: List<BookListItem>,
    letterOf: (BookListItem) -> Char?,
    progressOf: (BookListItem) -> Float,
    onOpenBook: (String) -> Unit,
) {
    var metrics by remember { mutableStateOf(Metrics(columns = 0, rowHeight = 0.0, headerHeight = 0.0)) }
    var scrollTop by remember { mutableStateOf(0.0) }
    var viewportHeight by remember { mutableStateOf(0.0) }

    ObserveScrollport(
        onMeasure = { top, height ->
            scrollTop = top
            viewportHeight = height
            metrics = measure(metrics)
        },
    )

    val rows = remember(books, metrics.columns, letterOf) { layOut(books, metrics.columns, letterOf) }

    // No scrollport means nothing to virtualise against — a spec mounting this component on its
    // own, or any future embedding outside the shell. Rendering the whole list is the honest
    // answer there: slower, but complete. Falling back to a *slice* would silently drop books and
    // their letter headers, which is what an earlier version of this did and what two specs caught.
    if (document.querySelector(SCROLLPORT) == null) {
        FlatGrid(books, letterOf, progressOf, onOpenBook)
        return
    }

    // Measured on the next frame; until then a screenful is rendered so there is something to
    // measure. Deliberately not the whole library — mounting 1,204 cards even once is the cost
    // this exists to avoid.
    if (!metrics.known) {
        FlatGrid(books.take(FIRST_PAINT_ROWS * ASSUMED_COLUMNS), letterOf, progressOf, onOpenBook)
        return
    }

    val offsets = remember(rows, metrics) { offsetsOf(rows, metrics) }
    val total = offsets.lastOrNull() ?: 0.0
    val firstVisible = rows.indices.lastOrNull { offsets[it] <= scrollTop - OVERSCAN_PX } ?: 0
    val lastVisible =
        rows.indices.firstOrNull { offsets[it] > scrollTop + viewportHeight + OVERSCAN_PX } ?: rows.size

    Div(attrs = { classes("lib-grid") }) {
        Spacer(offsets[firstVisible])
        for (index in firstVisible until lastVisible) {
            when (val row = rows[index]) {
                is GridRow.Header -> {
                    Div(attrs = { classes("lib-section") }) { Text(row.letter.toString()) }
                }

                is GridRow.Books -> {
                    row.items.forEach { book ->
                        BookCard(book = book, progress = progressOf(book), onOpen = { onOpenBook(book.id.value) })
                    }
                }
            }
        }
        Spacer(total - offsets[lastVisible.coerceAtMost(offsets.lastIndex)])
    }
}

/** The whole list, headers and all, with no windowing. See the call sites for when that is right. */
@Composable
private fun FlatGrid(
    books: List<BookListItem>,
    letterOf: (BookListItem) -> Char?,
    progressOf: (BookListItem) -> Float,
    onOpenBook: (String) -> Unit,
) {
    Div(attrs = { classes("lib-grid") }) {
        var letter: Char? = null
        books.forEach { book ->
            val next = letterOf(book)
            if (next != null && next != letter) {
                letter = next
                Div(attrs = { classes("lib-section") }) { Text(next.toString()) }
            }
            BookCard(book = book, progress = progressOf(book), onOpen = { onOpenBook(book.id.value) })
        }
    }
}

/** A full-width filler standing in for the rows that are not rendered. */
@Composable
private fun Spacer(pixels: Double) {
    if (pixels <= 0.0) return
    Div(attrs = {
        classes("lib-spacer")
        style { height(pixels.px) }
    })
}

/**
 * Watches the scrollport for movement and resizes.
 *
 * Scroll fires far more often than a frame, so the handler only records that something moved and
 * the read happens once per frame — reading `scrollTop` inside the event handler would force a
 * layout on every one of them.
 */
@Composable
private fun ObserveScrollport(onMeasure: (Double, Double) -> Unit) {
    DisposableEffect(Unit) {
        val port = document.querySelector(SCROLLPORT)
        var pending = false
        val read = {
            pending = false
            val node = document.querySelector(SCROLLPORT)?.asDynamic()
            if (node != null) {
                val top: Double = node.scrollTop
                val height: Double = node.clientHeight
                onMeasure(top, height)
            }
        }
        val schedule: (Event) -> Unit = {
            if (!pending) {
                pending = true
                window.requestAnimationFrame { read() }
            }
        }
        port?.addEventListener("scroll", schedule)
        window.addEventListener("resize", schedule)
        window.requestAnimationFrame { read() }
        onDispose {
            port?.removeEventListener("scroll", schedule)
            window.removeEventListener("resize", schedule)
        }
    }
}

/** Reads the grid's real geometry back out of the DOM, keeping [previous] when nothing is rendered yet. */
private fun measure(previous: Metrics): Metrics {
    val grid = document.querySelector(".lib-grid") ?: return previous
    val card = document.querySelector(".lib-card")?.asDynamic() ?: return previous
    val style = window.asDynamic().getComputedStyle(grid)
    val columnsText: String = style.gridTemplateColumns
    val columns = columnsText.split(" ").count { it.isNotBlank() }
    val gapText: String = style.rowGap
    val gap: Double = gapText.removeSuffix("px").toDoubleOrNull() ?: 0.0
    val cardHeight: Double = card.getBoundingClientRect().height
    val header = document.querySelector(".lib-section")?.asDynamic()
    val headerHeight: Double = if (header != null) header.getBoundingClientRect().height else previous.headerHeight
    if (columns <= 0 || cardHeight <= 0.0) return previous
    return Metrics(columns = columns, rowHeight = cardHeight + gap, headerHeight = headerHeight + gap)
}

/** Groups [books] into header and card rows of [columns] each, in the order they will be shown. */
private fun layOut(
    books: List<BookListItem>,
    columns: Int,
    letterOf: (BookListItem) -> Char?,
): List<GridRow> {
    if (columns <= 0) return emptyList()
    val rows = mutableListOf<GridRow>()
    var pending = mutableListOf<BookListItem>()
    var letter: Char? = null

    fun flush() {
        if (pending.isNotEmpty()) {
            rows.add(GridRow.Books(pending))
            pending = mutableListOf()
        }
    }
    books.forEach { book ->
        val next = letterOf(book)
        if (next != null && next != letter) {
            flush()
            letter = next
            rows.add(GridRow.Header(next))
        }
        pending.add(book)
        if (pending.size == columns) flush()
    }
    flush()
    return rows
}

/** Running top offset of every row, plus a final entry for the total height. */
private fun offsetsOf(
    rows: List<GridRow>,
    metrics: Metrics,
): List<Double> {
    val offsets = ArrayList<Double>(rows.size + 1)
    var running = 0.0
    rows.forEach { row ->
        offsets.add(running)
        running += if (row is GridRow.Header) metrics.headerHeight else metrics.rowHeight
    }
    offsets.add(running)
    return offsets
}

/** The scrolling ancestor the grid lives in. */
private const val SCROLLPORT = ".shell-main"

/** How far beyond the viewport to keep rendered, so a fast scroll does not outrun the window. */
private const val OVERSCAN_PX = 600

/** Rows rendered before anything has been measured — enough to fill a screen and be measured. */
private const val FIRST_PAINT_ROWS = 3

/** Only used for that first, pre-measurement slice; the real column count is read from the grid. */
private const val ASSUMED_COLUMNS = 8
