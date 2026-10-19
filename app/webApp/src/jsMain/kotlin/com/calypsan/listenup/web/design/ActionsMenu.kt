package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Button
import com.calypsan.listenup.web.motion.Keyframe
import com.calypsan.listenup.web.motion.MotionToken
import com.calypsan.listenup.web.motion.animateComposited
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event

/** One entry in an [ActionsMenu] or any other [PopupMenu]. */
data class MenuAction(
    val label: String,
    /** The item's leading glyph; null for a text-only item, such as a sort menu's choices. */
    val icon: WebIcon?,
    val onSelect: () -> Unit,
    /**
     * Non-null makes the item one of a set of choices — `menuitemradio`, saying whether it is the one
     * in force — rather than a command. A store picker's current store is `true`.
     */
    val checked: Boolean? = null,
    /**
     * Consecutive items sharing a group render inside one `role="group"` named by it, under a visible
     * heading ("Sort by", "Direction"). The arrows still walk every item, across groups.
     */
    val group: String? = null,
    /** With [checked] non-null, an on/off `menuitemcheckbox` rather than one of a set of radios. */
    val checkbox: Boolean = false,
    /** Choosing it leaves the menu open, for a toggle whose effect the reader should watch. */
    val keepsOpen: Boolean = false,
)

/**
 * A trailing "more actions" button and the menu it opens.
 *
 * Extracted from Book Detail's own menu when the admin inbox needed the same control. Both are a
 * row or a header with one primary gesture plus a few secondary ones, and a nested `<button>` is
 * invalid inside a `<button>` — so the secondary actions live beside the primary target, not
 * inside it. iOS's inbox row reaches the same arrangement and says so in the same words.
 *
 * ⛔ Renders nothing when [items] is empty. A button that opens an empty menu is worse than no
 * button: it promises something to do and then shows a blank sheet.
 */
@Composable
fun ActionsMenu(
    items: List<MenuAction>,
    label: String = "More actions",
    enabled: Boolean = true,
) {
    if (items.isEmpty()) return
    PopupMenu(
        items = items,
        label = label,
        icon = WebIcon.Grip,
        triggerClasses = buttonClasses(ButtonKind.Icon, ButtonSize.Lg),
        enabled = enabled,
    )
}

/**
 * A button that opens a menu, with the whole WAI-ARIA menu-button contract — the one shape the
 * account menu and every [ActionsMenu] share, so the two cannot drift apart in how a keyboard
 * reaches them.
 *
 * - The trigger says `aria-haspopup="menu"` and whether it is expanded.
 * - Opening puts focus on the first item, because opening a menu from the keyboard should put the
 *   reader inside it, not leave them on the button they pressed.
 * - The arrows walk the items and wrap; Home and End jump to the ends.
 * - Escape closes and hands focus back to the trigger. Tab closes and lets focus move on.
 * - A press anywhere outside closes. Pointer-down rather than click, so the menu is gone before
 *   the press lands on whatever was underneath it.
 *
 * [tooltip] repeats [label] as a `title` for an icon-only trigger whose purpose is not obvious from
 * its glyph (the account shield).
 *
 * [triggerText] draws words beside the trigger's glyph, for a menu whose trigger says what is chosen
 * now ("Audible store: United States"); [label] must then contain them, so the name a voice command
 * uses is the one on screen.
 */
@Composable
fun PopupMenu(
    items: List<MenuAction>,
    label: String,
    icon: WebIcon,
    triggerClasses: Array<String>,
    enabled: Boolean = true,
    tooltip: Boolean = false,
    triggerText: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { NodeHolder() }
    val trigger = remember { NodeHolder() }

    fun close(returnFocus: Boolean) {
        open = false
        if (returnFocus) trigger.element?.focus()
    }

    if (open) CloseOnOutsidePress(anchor) { open = false }

    // .menu-anchor, not .f-wrap: the latter is the form-field wrapper and is width:100%, which
    // stretched a menu across the whole content area.
    Div(attrs = {
        classes("menu-anchor")
        ref { element ->
            anchor.element = element
            onDispose { anchor.element = null }
        }
        onKeyDown { event ->
            val action = if (open) menuKeyAction(event.key) else null
            if (action != null) {
                val returnFocus = action == MenuKeyAction.CloseAndReturn
                // Tab keeps its default, so focus still moves on; Escape's is swallowed.
                if (returnFocus) event.preventDefault()
                close(returnFocus)
            }
        }
    }) {
        Button(attrs = {
            classes(*triggerClasses)
            attr("type", "button")
            attr("aria-label", label)
            if (tooltip) attr("title", label)
            attr("aria-haspopup", "menu")
            attr("aria-expanded", open.toString())
            if (!enabled) attr("disabled", "")
            ref { element ->
                trigger.element = element
                onDispose { trigger.element = null }
            }
            onClick { open = !open }
        }) {
            Icon(icon, size = MENU_ICON_SIZE)
            triggerText?.let { Text(it) }
        }

        if (open) {
            MenuItems(items = items, label = label, trigger = trigger, onChosen = { close(returnFocus = false) })
        }
    }
}

/** Calls [onOutside] for a press anywhere outside [anchor], for as long as this is composed. */
@Composable
private fun CloseOnOutsidePress(
    anchor: NodeHolder,
    onOutside: () -> Unit,
) {
    DisposableEffect(Unit) {
        val onPointerDown: (Event) -> Unit = { event ->
            val target = event.target as? Node
            if (target == null || anchor.element?.contains(target) != true) onOutside()
        }
        document.addEventListener("pointerdown", onPointerDown)
        onDispose { document.removeEventListener("pointerdown", onPointerDown) }
    }
}

/**
 * The open menu: real menu-item buttons, focus on the first, the arrows walking the rest.
 *
 * ⛔ It opens in the TOP LAYER (`popover`), placed against [trigger]'s on-screen box — never as an
 * absolutely-positioned child of the row it belongs to. The admin inbox's row menu was exactly that,
 * and its panel clips (`overflow: clip`): below a lone book, all that showed of the menu was a
 * sliver of its top edge. Any card, scroller or transformed box does the same, and no z-index
 * escapes a clip, so the menu leaves them all instead. Its DOM stays inside the anchor, so the
 * outside-press test, Escape and Tab still find it there.
 *
 * It hangs below the trigger from its left edge — right for the account menu at the content's left —
 * and measures itself as it opens: a menu that would cross the viewport's right edge (a trailing
 * "more actions" at the end of a row, on a phone) hangs from the trigger's right edge instead, and
 * one with no room below opens above. Measured rather than guessed from where the trigger sits,
 * because only the rendered menu knows how big its labels made it. It follows the trigger as
 * anything scrolls, since a fixed box would otherwise stay put while the row moves away.
 */
@Composable
private fun MenuItems(
    items: List<MenuAction>,
    label: String,
    trigger: NodeHolder,
    onChosen: () -> Unit,
) {
    val menuNode = remember { NodeHolder() }
    val lastFocused = remember { NodeHolder() }
    Div(attrs = {
        classes("menu")
        attr("role", "menu")
        attr("aria-label", label)
        // Manual: the menu keeps its own outside-press and Escape handling (PopupMenu).
        attr("popover", "manual")
        onFocusIn { event -> lastFocused.element = event.target as? HTMLElement }
        ref { menu ->
            menuNode.element = menu
            menu.showInTopLayer()
            // A short drop as it opens; the resting state is the menu's own style (see Motion.kt).
            animateComposited(
                menu,
                listOf(Keyframe.transform("translateY(-4px)"), Keyframe.transform("none")),
                MotionToken.ENTER,
            )

            fun placeAgainstTrigger() {
                trigger.element?.let { placeMenu(menu, it) }
            }
            placeAgainstTrigger()
            val place: (Event) -> Unit = { placeAgainstTrigger() }
            // Placed first, focused second, and without scrolling: focus used to scroll a clipping
            // box to reveal the menu, dragging its row out from under the reader.
            (menu.querySelector("[role^=menuitem]") as? HTMLElement)?.focusWithoutScrolling()
            val view = menu.ownerDocument?.defaultView
            // Capture, so a scroll inside any box — not just the page — moves the menu with its row.
            view?.addEventListener("scroll", place, true)
            view?.addEventListener("resize", place)
            onDispose {
                menuNode.element = null
                view?.removeEventListener("scroll", place, true)
                view?.removeEventListener("resize", place)
            }
        }
    }) {
        // One flat index across every item, so the first takes focus and the arrows walk them all,
        // whichever group each sits in.
        val indexed = items.withIndex().toList()
        runsByGroup(indexed).forEach { run ->
            val group = run.first().value.group
            if (group == null) {
                run.forEach { (index, item) -> MenuItem(item, index, items.size, menuNode, onChosen) }
            } else {
                Div(attrs = {
                    attr("role", "group")
                    attr("aria-label", group)
                }) {
                    // The group's name is already its label; the heading is for the eye.
                    Span(attrs = {
                        classes("menu-h")
                        attr("aria-hidden", "true")
                    }) { Text(group) }
                    run.forEach { (index, item) -> MenuItem(item, index, items.size, menuNode, onChosen) }
                }
            }
        }
    }
    // Compose re-applies EVERY attribute of the menu when any one of them changes, and taking
    // `popover` off closes it. A kept-open sort menu re-words its name when its direction flips, so
    // the menu shows itself again in the same frame, and focus goes back to the item that had it.
    SideEffect {
        if (menuNode.element?.reopenInTopLayer() == true) lastFocused.element?.focusWithoutScrolling()
    }
}

/** [items] cut into runs of consecutive items sharing a [MenuAction.group] (null is a run too). */
private fun runsByGroup(items: List<IndexedValue<MenuAction>>): List<List<IndexedValue<MenuAction>>> {
    val runs = mutableListOf<MutableList<IndexedValue<MenuAction>>>()
    items.forEach { entry ->
        val last = runs.lastOrNull()
        if (last != null &&
            last.first().value.group == entry.value.group
        ) {
            last += entry
        } else {
            runs += mutableListOf(entry)
        }
    }
    return runs
}

/** One menu item at [index] of [count]: a command, a radio of a set, or a checkbox. */
@Composable
private fun MenuItem(
    item: MenuAction,
    index: Int,
    count: Int,
    menu: NodeHolder,
    onChosen: () -> Unit,
) {
    // ⛔ A real <button role="menuitem">: a clickable <div> is unreachable by keyboard and
    // announces nothing.
    Button(attrs = {
        classes("menu-i")
        attr("type", "button")
        attr(
            "role",
            when {
                item.checked == null -> "menuitem"
                item.checkbox -> "menuitemcheckbox"
                else -> "menuitemradio"
            },
        )
        item.checked?.let { attr("aria-checked", it.toString()) }
        // Only the first item is a Tab stop; the arrows are how a menu is walked.
        attr("tabindex", if (index == 0) "0" else "-1")
        onKeyDown { event ->
            rovingTarget(event.key, index, count, RovingAxis.Vertical)?.let { next ->
                event.preventDefault()
                // From the menu, not the item's siblings: a group puts the next item in another parent.
                (menu.element?.querySelectorAll("[role^=menuitem]")?.item(next) as? HTMLElement)?.focus()
            }
        }
        onClick {
            if (!item.keepsOpen) onChosen()
            item.onSelect()
        }
    }) {
        item.icon?.let { Icon(it, size = MENU_ICON_SIZE) }
        Text(item.label)
    }
}

/**
 * Puts [this] in the top layer, above every stacking context and outside every clip. Where the
 * popover API is missing, the `.menu` rule's `position: fixed` is the fallback, which still escapes
 * every clip but a transformed ancestor's.
 */
private fun HTMLElement.showInTopLayer() {
    val element: dynamic = this
    if (jsTypeOf(element.showPopover) == "function") element.showPopover()
}

/** Shows [this] again in the top layer if an attribute pass closed it; true when it had to. */
private fun HTMLElement.reopenInTopLayer(): Boolean {
    val element: dynamic = this
    val closed = jsTypeOf(element.showPopover) == "function" && !(element.matches(":popover-open") as Boolean)
    if (closed) element.showPopover()
    return closed
}

private fun HTMLElement.focusWithoutScrolling() {
    asDynamic().focus(js("({ preventScroll: true })"))
}

/**
 * Places [menu] against [trigger]'s on-screen box: below it and from its left edge by preference,
 * from its right edge when the left would cross the viewport's, above it when only above has room,
 * and — when neither side has room for all of it — on the roomier side, scrolling within the space
 * there.
 */
private fun placeMenu(
    menu: HTMLElement,
    trigger: HTMLElement,
) {
    val viewport = menu.ownerDocument?.documentElement ?: return
    val viewportWidth = viewport.clientWidth.toDouble()
    val viewportHeight = viewport.clientHeight.toDouble()
    val anchor = trigger.getBoundingClientRect()

    menu.style.removeProperty("max-height")
    val width = menu.offsetWidth.toDouble()
    val height = menu.offsetHeight.toDouble()

    val fromLeft = anchor.left
    val left =
        (if (fromLeft + width > viewportWidth - VIEWPORT_GUTTER) anchor.right - width else fromLeft)
            .coerceAtMost(viewportWidth - VIEWPORT_GUTTER - width)
            .coerceAtLeast(VIEWPORT_GUTTER)

    val below = anchor.bottom + TRIGGER_GAP
    val roomBelow = viewportHeight - VIEWPORT_GUTTER - below
    val roomAbove = anchor.top - TRIGGER_GAP - VIEWPORT_GUTTER
    val opensBelow = height <= roomBelow || (height > roomAbove && roomBelow >= roomAbove)
    val room = if (opensBelow) roomBelow else roomAbove
    if (height > room) menu.style.setProperty("max-height", "${room.coerceAtLeast(0.0)}px")
    val top = if (opensBelow) below else anchor.top - TRIGGER_GAP - minOf(height, room)

    menu.style.setProperty("left", "${left}px")
    menu.style.setProperty("top", "${top}px")
}

/** How close to the viewport's edge a menu may come before it flips to the other side. */
private const val VIEWPORT_GUTTER = 8.0

/** The space between a trigger and the menu hanging from it. */
private const val TRIGGER_GAP = 6.0

/** What a key does to an open menu, beyond the arrows its items handle themselves. */
private enum class MenuKeyAction {
    /** Escape: close, and hand focus back to the trigger. */
    CloseAndReturn,

    /** Tab: close, and let focus move on as Tab would anyway. */
    CloseAndMoveOn,
}

private fun menuKeyAction(key: String): MenuKeyAction? =
    when (key) {
        "Escape" -> MenuKeyAction.CloseAndReturn
        "Tab" -> MenuKeyAction.CloseAndMoveOn
        else -> null
    }

/** A DOM node captured by `ref`, for the two places a menu has to reach past composition. */
private class NodeHolder {
    var element: HTMLElement? = null
}

private const val MENU_ICON_SIZE = 18
