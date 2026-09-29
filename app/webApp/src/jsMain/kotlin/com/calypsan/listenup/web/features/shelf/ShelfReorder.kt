package com.calypsan.listenup.web.features.shelf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.presentation.shelf.reorderedBy
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.pointerevents.PointerEvent

/**
 * The controls on a shelf row that can take keyboard focus through a reorder, so focus can follow
 * the book to where it landed rather than staying on whatever row now sits where it was.
 */
internal enum class ShelfControl {
    Grip,
    Earlier,
    Later,
}

/** A finger drag on a grip in progress: which pointer, the row it lifted, and the row it is over. */
internal data class TouchDrag(
    val pointerId: Int,
    val from: Int,
    val over: Int,
)

/** `"Emma moved to position 2 of 3."` — what the live region says after a move. */
internal fun moveAnnouncement(
    title: String,
    position: Int,
    count: Int,
): String = "$title moved to position $position of $count."

/**
 * Move earlier / Move later: the non-drag way to reorder, visible on every row — the same two moves
 * Android offers as accessibility actions (`ShelfReorderActions`). A move that would fall off either
 * end is disabled rather than hidden, so the buttons down a shelf stay in one column.
 *
 * [register] hands each button's element to the shelf, which is how focus finds it again after the
 * row moves.
 */
@Composable
internal fun MoveButtons(
    title: String,
    index: Int,
    lastIndex: Int,
    onMove: (to: Int) -> Unit,
    register: (ShelfControl, HTMLElement?) -> Unit,
) {
    MoveButton(ShelfControl.Earlier, "Move $title earlier", WebIcon.ArrowUp, enabled = index > 0, register) {
        onMove(index - 1)
    }
    MoveButton(ShelfControl.Later, "Move $title later", WebIcon.ArrowDown, enabled = index < lastIndex, register) {
        onMove(index + 1)
    }
}

@Composable
private fun MoveButton(
    control: ShelfControl,
    label: String,
    icon: WebIcon,
    enabled: Boolean,
    register: (ShelfControl, HTMLElement?) -> Unit,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("shelf-move")
        attr("type", "button")
        attr("aria-label", label)
        attr("title", label)
        if (!enabled) attr("disabled", "")
        registerAs(control, register)
        onClick { onClick() }
    }) { Icon(icon, size = MOVE_ICON_SIZE) }
}

/** Hands this element to [register] while it is in the DOM, and takes it back when it leaves. */
internal fun AttrsScope<out HTMLElement>.registerAs(
    control: ShelfControl,
    register: (ShelfControl, HTMLElement?) -> Unit,
) {
    ref { element ->
        register(control, element)
        onDispose { register(control, null) }
    }
}

/**
 * Where a move is said out loud. Polite, and visually hidden: a sighted reader sees the row move, a
 * screen-reader user hears where it went — otherwise the list rearranges under them in silence.
 */
@Composable
internal fun ShelfAnnouncer(message: String) {
    Div(attrs = {
        classes("sr-only")
        attr("role", "status")
        attr("aria-live", "polite")
    }) { Text(message) }
}

/**
 * A shelf's reorder state and the one place every reorder goes through.
 *
 * [move] is shared by all four gestures (row drag, grip drag, the move buttons, the grip's arrow
 * keys), so each says where the book landed in the same words and none can drift into sending a
 * different order. [restoreFocus] puts focus back on the control that moved a book once the new
 * order has rendered: the row's elements move with it, and a browser drops focus from a node it
 * moves.
 */
internal class ShelfReorder(
    private val books: State<List<ShelfBook>>,
    private val onReorder: State<(List<String>) -> Unit>,
) {
    /** What the live region last said. */
    var announcement by mutableStateOf("")
        private set

    /** The row an HTML5 (mouse) drag lifted, until it drops. */
    var draggingIndex by mutableStateOf<Int?>(null)

    /** A finger drag on a grip, until it lifts or the browser takes it back. */
    var touchDrag by mutableStateOf<TouchDrag?>(null)

    private var refocus: Refocus? = null
    private val controls = mutableMapOf<Pair<String, ShelfControl>, HTMLElement>()

    /** Moves the book at [from] to [to], announces it, and — if [focusAfter] — keeps focus on it. */
    fun move(
        from: Int,
        to: Int,
        focusAfter: ShelfControl? = null,
    ) {
        val current = books.value
        val reordered = reorderedBy(current, from, to)
        if (reordered === current) return
        val moved = current[from]
        val landing = reordered.indexOf(moved)
        announcement = moveAnnouncement(moved.title, position = landing + 1, count = current.size)
        refocus = focusAfter?.let { Refocus(moved.idString, it, landing) }
        onReorder.value(reordered.map { it.idString })
    }

    /** How a row's focusable controls hand their elements to [restoreFocus], keyed by [bookId]. */
    fun registrar(bookId: String): (ShelfControl, HTMLElement?) -> Unit =
        { control, element ->
            if (element == null) {
                controls.remove(bookId to control)
            } else {
                controls[bookId to control] = element
            }
        }

    /**
     * Once [rendered] is on screen: back to the control that moved the book, or — when that move has
     * just become impossible (the book reached an end) — to the move it still has. Consumed only by
     * the order it was waiting for, so an effect still running for the old order, or a store that
     * has not answered yet, leaves it alone.
     */
    suspend fun restoreFocus(rendered: List<ShelfBook>) {
        val pending = refocus ?: return
        if (rendered.indexOfFirst { it.idString == pending.bookId } != pending.landing) return
        refocus = null
        // A frame first: the browser settles focus after a render (a moved or newly disabled control
        // loses it), and focus set before that would be undone.
        withFrameNanos { }
        val usable =
            when {
                pending.control == ShelfControl.Earlier && pending.landing == 0 -> ShelfControl.Later
                pending.control == ShelfControl.Later && pending.landing == rendered.lastIndex -> ShelfControl.Earlier
                else -> pending.control
            }
        controls[pending.bookId to usable]?.focus()
    }
}

/** Where focus goes once a move has rendered: [control] on [bookId]'s row, now at [landing]. */
private class Refocus(
    val bookId: String,
    val control: ShelfControl,
    val landing: Int,
)

/** A [ShelfReorder] for this shelf, restoring focus each time a new order renders. */
@Composable
internal fun rememberShelfReorder(
    books: List<ShelfBook>,
    onReorder: (List<String>) -> Unit,
): ShelfReorder {
    val currentBooks = rememberUpdatedState(books)
    val currentOnReorder = rememberUpdatedState(onReorder)
    val reorder = remember { ShelfReorder(currentBooks, currentOnReorder) }
    LaunchedEffect(books) { reorder.restoreFocus(books) }
    return reorder
}

/**
 * The row's own HTML5 drag, for a mouse. The dragged row's index is held in [ShelfReorder] rather
 * than read back out of the DOM, because the only thing a `drop` event reliably carries is where it
 * landed. `dragover` must call `preventDefault()` or the browser refuses the drop: the default for a
 * dragged-over element is "not a drop target", and there is no declarative way to say otherwise.
 */
internal fun AttrsScope<out HTMLElement>.shelfRowDrag(
    index: Int,
    reorder: ShelfReorder,
) {
    reorder.touchDrag?.let { drag ->
        if (drag.from == index) classes("is-lifted")
        if (drag.over == index && drag.over != drag.from) classes("is-target")
    }
    attr("draggable", "true")
    onDragStart { event ->
        // A finger already dragging the grip owns the gesture; a long press must not start a
        // second, HTML5 drag of the whole row under it.
        if (reorder.touchDrag != null) event.preventDefault() else reorder.draggingIndex = index
    }
    onDragEnd { reorder.draggingIndex = null }
    onDragOver { event -> event.preventDefault() }
    onDrop { event ->
        event.preventDefault()
        reorder.draggingIndex?.let { from -> reorder.move(from, index) }
        reorder.draggingIndex = null
    }
}

/**
 * The finger drag on a grip, as pointer events. HTML5 drag-and-drop is left to the mouse, which is
 * the only pointer that reliably starts one; a touch or a pen drags here instead.
 *
 * The grip carries `touch-action: none`, so the browser hands the finger to this code rather than
 * scrolling the page. Capture keeps the drag alive once the finger leaves the grip. A drag ends by
 * lifting (a drop, at the row under the finger), or by `pointercancel` / `lostpointercapture` — the
 * browser taking the gesture back — which drops nothing.
 */
internal fun AttrsScope<out HTMLElement>.touchDragOn(
    index: Int,
    reorder: ShelfReorder,
) {
    addEventListener(POINTER_DOWN) { event ->
        val press = event.nativeEvent as PointerEvent
        if (press.pointerType == MOUSE) return@addEventListener
        press.preventDefault()
        capture(press)
        reorder.touchDrag = TouchDrag(press.pointerId, from = index, over = index)
    }
    addEventListener(POINTER_MOVE) { event ->
        val motion = event.nativeEvent as PointerEvent
        val drag = reorder.touchDrag?.takeIf { it.pointerId == motion.pointerId } ?: return@addEventListener
        val over = rowIndexAt(motion) ?: return@addEventListener
        if (over != drag.over) reorder.touchDrag = drag.copy(over = over)
    }
    addEventListener(POINTER_UP) { event ->
        val release = event.nativeEvent as PointerEvent
        val drag = reorder.touchDrag?.takeIf { it.pointerId == release.pointerId } ?: return@addEventListener
        reorder.touchDrag = null
        reorder.move(drag.from, rowIndexAt(release) ?: drag.over)
    }
    listOf(POINTER_CANCEL, LOST_CAPTURE).forEach { ending ->
        addEventListener(ending) { event ->
            val pointer = event.nativeEvent as PointerEvent
            if (reorder.touchDrag?.pointerId == pointer.pointerId) reorder.touchDrag = null
        }
    }
}

/** A synthetic or already-released pointer refuses capture; the drag still works over the grip. */
private fun capture(press: PointerEvent) {
    try {
        (press.currentTarget as? Element)?.asDynamic()?.setPointerCapture(press.pointerId)
    } catch (_: Throwable) {
        Unit
    }
}

/**
 * The row under [event]'s pointer, by height alone: the first row whose bottom edge is below it, or
 * the last row when the finger is past the end. Read from the rendered rows each time, because a
 * shelf that re-renders mid-drag moves them.
 */
private fun rowIndexAt(event: PointerEvent): Int? {
    val grip = event.currentTarget as? Element ?: return null
    val rows =
        grip
            .closest(".shelf-books")
            ?.querySelectorAll(":scope > .shelf-book")
            ?.asList()
            ?.filterIsInstance<Element>()
            .orEmpty()
    if (rows.isEmpty()) return null
    val y = event.clientY.toDouble()
    return rows.indexOfFirst { y < it.getBoundingClientRect().bottom }.takeIf { it >= 0 } ?: rows.lastIndex
}

private const val POINTER_DOWN = "pointerdown"

private const val POINTER_MOVE = "pointermove"

private const val POINTER_UP = "pointerup"

private const val POINTER_CANCEL = "pointercancel"

private const val LOST_CAPTURE = "lostpointercapture"

private const val MOUSE = "mouse"

private const val MOVE_ICON_SIZE = 16
