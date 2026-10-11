package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.calypsan.listenup.web.motion.staggerOnArrival
import kotlinx.browser.window
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The scrolling region a [VirtualList] windows against — the shell's content region.
 *
 * Provided by the shell rather than looked up in the document, for two reasons. A list must decide
 * *during composition* whether it can virtualise at all (see [VirtualList]), before any element
 * exists to look from. And a document-wide `querySelector` answers for the wrong document when the
 * shell renders inside a frame — which is how the phone specs measure — or for a shell that happens
 * to be mounted elsewhere on the page.
 */
class Scrollport {
    /** The live element, once mounted. Not state: reading it must not recompose anything. */
    var element: HTMLElement? = null
}

/** The enclosing [Scrollport], or null when this composition is not inside the shell. */
val LocalScrollport = staticCompositionLocalOf<Scrollport?> { null }

/**
 * A list or grid that renders only the rows near the viewport.
 *
 * ⛔ **Why this exists.** The unvirtualised library grid put all 1,204 books in the DOM. Measured
 * against the real library, that cost **2,525 ms** to re-sort and **4,064 ms** to navigate away from
 * — and the DOM was not the culprit: detaching and reattaching all 4,841 nodes by hand took
 * **68 ms** combined. The seconds were Compose, diffing and recomposing 1,204 composables. So the
 * fix has to reduce the number of *composables*, which is what this does.
 *
 * **Layout.** [containerClass] names the container's own CSS: a `display:grid` with `auto-fill`
 * columns lays [items] out in as many columns as fit, and anything else is one column. A row is
 * either a [header] (whenever [sectionOf] changes, spanning the width) or up to one row's worth of
 * items. Row offsets are arithmetic, not measurement, which is only possible because every item is
 * exactly the same height, and every header too — the callers' stylesheets clamp their text for
 * exactly that reason. Heights are read from the first rendered [itemSelector] and [headerSelector]
 * rather than copied from the sheet: a responsive grid changes its column width with the window,
 * and a Kotlin copy of the CSS would be a second source of truth.
 *
 * **Keys.** Every item and header is rendered under `key(...)`, so a book that moves from one row
 * to the next keeps its DOM node, its cover `<img>` and its remembered state — without it the grid
 * rewrote each tile's `src` whenever the window shifted by a row, and a focused tile became a
 * different book under the reader's cursor.
 *
 * **Accessibility.** The container is a `list` named [label], and each item a `listitem` carrying
 * `aria-posinset`/`aria-setsize`, so a screen reader announces "12 of 1,204" although only a
 * screenful exists. The item wrappers are `display:contents`, so the caller's own grid still lays
 * out the caller's own element. Headers are hidden from assistive technology: they are a visual
 * index over a list whose items already name themselves, and a list may only own list items.
 * Keyboard focus reaches items outside the window because focusing an item scrolls it into view,
 * the overscan keeps the next rows rendered beyond the edge, and the scroll moves the window.
 *
 * **Fallback.** Outside a [LocalScrollport] — a spec mounting a page on its own — the whole list is
 * rendered: slower, but complete. Rendering a *slice* there would silently drop items, which is
 * what an earlier version of the library grid did and what two specs caught.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun <T> VirtualList(
    items: List<T>,
    key: (T) -> Any,
    containerClass: String,
    itemSelector: String,
    label: String,
    sectionOf: (T) -> Char? = { null },
    headerSelector: String? = null,
    header: @Composable (Char) -> Unit = {},
    item: @Composable (T) -> Unit,
) {
    val scrollport = LocalScrollport.current
    var metrics by remember { mutableStateOf(RowMetrics.UNKNOWN) }
    var rowWindow by remember { mutableStateOf(RowWindow.NONE) }
    val tracker = remember { WindowTracker() }
    val rows = remember(items, metrics.columns, sectionOf) { layOut(items, metrics.columns, sectionOf) }
    val offsets = remember(rows, metrics) { offsetsOf(rows, metrics) }
    SideEffect { tracker.offsets = offsets }
    val selectors = rememberUpdatedState(itemSelector to headerSelector)
    val positions = remember(items, key) { items.withIndex().associate { (index, entry) -> key(entry) to index + 1 } }

    Div(attrs = {
        classes(containerClass)
        attr("role", "list")
        attr("aria-label", label)
        if (scrollport != null) {
            ref { element ->
                val port = scrollport.element ?: element.closest(SCROLLPORT_SELECTOR) as? HTMLElement
                val detach =
                    port?.let {
                        observeScrollport(
                            container = element,
                            port = it,
                            onResize = {
                                val (itemsAt, headersAt) = selectors.value
                                metrics = measure(element, itemsAt, headersAt, metrics)
                            },
                            onScroll = { view ->
                                tracker.view = view
                                val next = windowOf(tracker.offsets, view)
                                // ⛔ The only per-frame state write, and only when it changes what is
                                // rendered. Scrolling within a row used to write the scroll offset
                                // every frame, recomposing the whole list sixty times a second to
                                // produce the same DOM.
                                if (next != rowWindow) rowWindow = next
                            },
                        )
                    }
                tracker.container = element
                // The first screenful sweeps in when the list arrives with its page. See Stagger.kt.
                staggerOnArrival(element)
                onDispose {
                    detach?.invoke()
                    tracker.container = null
                }
            }
        }
    }) {
        // Reported from this scope, not the function's: it is the one that re-runs when the window
        // moves, and the one holding the loop over every rendered item.
        val probe = LocalCompositionProbe.current
        probe(VIRTUAL_LIST_PROBE)
        val virtualised = scrollport != null && metrics.known
        val shown = remember(offsets, rowWindow) { windowOf(offsets, tracker.view) }
        val shownRows =
            when {
                scrollport == null -> layOut(items, columns = 1, sectionOf)

                // Measured on the next frame; until then a screenful is rendered so there is
                // something to measure. Deliberately not the whole list — mounting 1,204 cards even
                // once is the cost this exists to avoid.
                !metrics.known -> layOut(items.take(FIRST_PAINT_ITEMS), columns = 1, sectionOf)

                else -> rows.subList(shown.first, shown.last)
            }
        if (virtualised) Spacer(offsets[shown.first])
        // ⛔ One flat run of keys under this one parent, the key outermost in each iteration. A key
        // only matches among its siblings: emitted inside a per-row call, or inside the branch of a
        // `when` over the row, it is scoped to that row's slot in the loop — and when the window
        // moves by a row every slot holds a different row, which is exactly the churn keys prevent.
        cellsOf(shownRows, key).forEach { cell ->
            key(cell.key) {
                when (cell) {
                    is Cell.Head -> Header(cell.section, header)
                    is Cell.Entry -> Item(cell.entry, positions[cell.key], items.size, item)
                }
            }
        }
        if (virtualised) Spacer(offsets.last() - offsets[shown.last])
    }
    // ⛔ The first paint is a fixed [FIRST_PAINT_ITEMS]; on a wide window the measured window mounts
    // more a frame later, and swept only on mount those would appear at once while the tiles above
    // them were still fading in. So the sweep runs again over the whole screenful once it exists —
    // once, on the first measurement, never on a later resize.
    val measured = metrics.known
    DisposableEffect(measured) {
        if (measured) tracker.container?.let(::staggerOnArrival)
        onDispose { }
    }
}

/** A section header: visual only — see [VirtualList] on why assistive technology skips it. */
@Composable
private fun Header(
    section: Char,
    header: @Composable (Char) -> Unit,
) {
    Div(attrs = {
        classes("vl-head")
        attr("aria-hidden", "true")
    }) { header(section) }
}

/** One item, as a list item that knows where it sits in the whole list rather than the window. */
@Composable
private fun <T> Item(
    entry: T,
    position: Int?,
    setSize: Int,
    item: @Composable (T) -> Unit,
) {
    Div(attrs = {
        classes("vl-item")
        attr("role", "listitem")
        attr("aria-setsize", setSize.toString())
        position?.let { attr("aria-posinset", it.toString()) }
    }) { item(entry) }
}

/** Distinguishes a header's key from an item whose own key happens to be the same letter. */
private data class HeaderKey(
    val section: Char,
)

/** One emitted element of the list: a header or an item, with the key it is rendered under. */
private sealed interface Cell<out T> {
    val key: Any

    data class Head(
        val section: Char,
    ) : Cell<Nothing> {
        override val key: Any get() = HeaderKey(section)
    }

    data class Entry<T>(
        val entry: T,
        override val key: Any,
    ) : Cell<T>
}

/** [rows], flattened to the run of cells the list emits. */
private fun <T> cellsOf(
    rows: List<ListRow<T>>,
    key: (T) -> Any,
): List<Cell<T>> =
    rows.flatMap { row ->
        when (row) {
            is ListRow.Header -> listOf(Cell.Head(row.section))
            is ListRow.Items -> row.items.map { Cell.Entry(it, key(it)) }
        }
    }

/** A full-width filler standing in for the rows that are not rendered. */
@Composable
private fun Spacer(pixels: Double) {
    if (pixels <= 0.0) return
    Div(attrs = {
        classes("vl-spacer")
        attr("aria-hidden", "true")
        style { height(pixels.px) }
    })
}

/** One row of the list: a section header, or up to one grid row's worth of items. */
private sealed interface ListRow<out T> {
    data class Header(
        val section: Char,
    ) : ListRow<Nothing>

    data class Items<T>(
        val items: List<T>,
    ) : ListRow<T>
}

/** What the list needs to know to turn a scroll offset into a range of rows. */
private data class RowMetrics(
    val columns: Int,
    val rowHeight: Double,
    val headerHeight: Double,
) {
    val known: Boolean get() = columns > 0 && rowHeight > 0.0

    companion object {
        val UNKNOWN = RowMetrics(columns = 0, rowHeight = 0.0, headerHeight = 0.0)
    }
}

/** Where the viewport is, relative to the list's own top. */
private data class ListView(
    val top: Double,
    val height: Double,
)

/** The rows `[first, last)` to render. */
private data class RowWindow(
    val first: Int,
    val last: Int,
) {
    companion object {
        val NONE = RowWindow(0, 0)
    }
}

/**
 * What the scroll handler reads between compositions. Plain fields, not state: the handler runs
 * every frame, and only a change of [RowWindow] is allowed to cause a recomposition.
 */
private class WindowTracker {
    var offsets: List<Double> = emptyList()
    var view: ListView = ListView(top = 0.0, height = 0.0)

    /** The list's own element, for the sweep that follows its first measurement. */
    var container: Element? = null
}

/** Rows within [OVERSCAN_PX] of the viewport, clamped to what exists. */
private fun windowOf(
    offsets: List<Double>,
    view: ListView,
): RowWindow {
    val rowCount = (offsets.size - 1).coerceAtLeast(0)
    if (rowCount == 0) return RowWindow.NONE
    val first = (0 until rowCount).lastOrNull { offsets[it] <= view.top - OVERSCAN_PX } ?: 0
    val last =
        (0 until rowCount).firstOrNull { offsets[it] > view.top + view.height + OVERSCAN_PX } ?: rowCount
    return RowWindow(first, last.coerceAtLeast(first))
}

/**
 * Watches the scrollport for movement, and the list and the scrollport for resizing.
 *
 * Scroll fires far more often than a frame, so the handler only records that something moved and
 * the read happens once per frame — reading `scrollTop` inside the event handler would force a
 * layout on every one of them.
 *
 * ⛔ Resizes come from a `ResizeObserver` on the list itself, not from the window's `resize`
 * event. Collapsing the sidebar changes the list's width by 176px without the window changing at
 * all, and a window listener left the column count — and so every row offset — stale until the
 * reader happened to resize the browser. The observer also fires once on attach, which is the
 * first measurement.
 *
 * ⛔ Geometry is re-measured on resize ONLY, never per scroll frame. Measuring reads whichever item
 * is currently first in the DOM, so measuring while scrolling samples a different element each
 * time; a row height that wobbles by a pixel moves every offset below it, changes the total
 * height, and the browser lurches. Geometry depends on the width, not on where the reader is.
 */
private fun observeScrollport(
    container: Element,
    port: HTMLElement,
    onResize: () -> Unit,
    onScroll: (ListView) -> Unit,
): () -> Unit {
    var pending = false
    val read = {
        pending = false
        // How far the list's top is above the scrollport's: the list rarely starts at the top of
        // the page (a header and a facet row sit above it), so the port's own scrollTop is not it.
        val top = port.getBoundingClientRect().top - container.getBoundingClientRect().top
        onScroll(ListView(top = top, height = port.clientHeight.toDouble()))
    }
    val schedule: (Event) -> Unit = {
        if (!pending) {
            pending = true
            window.requestAnimationFrame { read() }
        }
    }
    val resizes =
        ResizeObserver { _, _ ->
            onResize()
            read()
        }
    port.addEventListener("scroll", schedule)
    resizes.observe(container)
    resizes.observe(port)
    return {
        port.removeEventListener("scroll", schedule)
        resizes.disconnect()
    }
}

/** The browser's `ResizeObserver`, as much of it as the list and the tab ink use. */
internal external class ResizeObserver(
    callback: (entries: dynamic, observer: dynamic) -> Unit,
) {
    /** [options] as the browser takes them, e.g. `{ box: "border-box" }`; content-box when omitted. */
    fun observe(
        target: Element,
        options: dynamic = definedExternally,
    )

    fun disconnect()
}

/** Reads the list's real geometry back out of the DOM, keeping [previous] when nothing is rendered yet. */
private fun measure(
    container: Element,
    itemSelector: String,
    headerSelector: String?,
    previous: RowMetrics,
): RowMetrics {
    val item = container.querySelector(itemSelector) ?: return previous
    val style = window.getComputedStyle(container)
    // `none` for anything that is not a grid: a plain list is one column.
    val columns =
        style
            .getPropertyValue("grid-template-columns")
            .split(" ")
            .count { it.isNotBlank() && it != "none" }
            .coerceAtLeast(1)
    val gap = style.getPropertyValue("row-gap").removeSuffix("px").toDoubleOrNull() ?: 0.0
    val itemHeight = outerHeight(item)
    val header = headerSelector?.let { container.querySelector(it) }
    val headerHeight = header?.let { outerHeight(it) + gap } ?: previous.headerHeight
    if (itemHeight <= 0.0) return previous
    return RowMetrics(columns = columns, rowHeight = itemHeight + gap, headerHeight = headerHeight)
}

/** The height an element takes in the flow: its box plus its vertical margins. */
private fun outerHeight(element: Element): Double {
    val style = window.getComputedStyle(element)
    val margins =
        listOf("margin-top", "margin-bottom").sumOf {
            style.getPropertyValue(it).removeSuffix("px").toDoubleOrNull() ?: 0.0
        }
    return element.getBoundingClientRect().height + margins
}

/** Groups [items] into header and item rows of [columns] each, in the order they will be shown. */
private fun <T> layOut(
    items: List<T>,
    columns: Int,
    sectionOf: (T) -> Char?,
): List<ListRow<T>> {
    if (columns <= 0) return emptyList()
    val rows = mutableListOf<ListRow<T>>()
    var pending = mutableListOf<T>()
    var section: Char? = null

    fun flush() {
        if (pending.isNotEmpty()) {
            rows.add(ListRow.Items(pending))
            pending = mutableListOf()
        }
    }
    items.forEach { entry ->
        val next = sectionOf(entry)
        if (next != null && next != section) {
            flush()
            section = next
            rows.add(ListRow.Header(next))
        }
        pending.add(entry)
        if (pending.size == columns) flush()
    }
    flush()
    return rows
}

/** Running top offset of every row, plus a final entry for the total height. */
private fun <T> offsetsOf(
    rows: List<ListRow<T>>,
    metrics: RowMetrics,
): List<Double> {
    val offsets = ArrayList<Double>(rows.size + 1)
    var running = 0.0
    rows.forEach { row ->
        offsets.add(running)
        running += if (row is ListRow.Header) metrics.headerHeight else metrics.rowHeight
    }
    offsets.add(running)
    return offsets
}

/** What the list reports to [LocalCompositionProbe] each time its body runs. */
internal const val VIRTUAL_LIST_PROBE = "virtual-list"

/** The shell's content region, for a list whose [Scrollport] has not recorded its element. */
private const val SCROLLPORT_SELECTOR = ".shell-main"

/** How far beyond the viewport to keep rendered, so a fast scroll does not outrun the window. */
private const val OVERSCAN_PX = 600

/** Items rendered before anything has been measured — enough to fill a screen and be measured. */
private const val FIRST_PAINT_ITEMS = 24
