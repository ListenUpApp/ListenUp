package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.A
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
    /** Destroys something: drawn in the danger colour, in a group of its own with no visible heading. */
    val destructive: Boolean = false,
    /** False draws the item disabled (it emits `disabled`, never `disabled="false"`) and choosing it does nothing. */
    val enabled: Boolean = true,
    /**
     * Non-null makes the item a real link to this URL: a person or a page the reader could open in a new tab, or a
     * document. A plain click is routed in-app through [onSelect]; a modified one keeps the browser's default.
     */
    val href: String? = null,
    /** With [href], the link opens in a new tab (`target=_blank`, `rel=noopener`) and [onSelect] only closes the menu. */
    val opensNewTab: Boolean = false,
    /**
     * A second, quieter line under [label] — a person's "14 books" (§5.5.5). It is read into the item's accessible name
     * ("Michael Kramer, 14 books"), so a screen reader hears what the eye sees.
     */
    val detail: String? = null,
    /**
     * The key that does the same thing, as `aria-keyshortcuts` spells it ("?"), drawn at the item's trailing edge:
     * More's Keyboard shortcuts row (spec §5.4).
     */
    val keyShortcut: String? = null,
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
 * - Escape closes and hands focus back to the trigger, as choosing an item does. Tab closes and
 *   lets focus move on.
 * - A press anywhere outside closes. Pointer-down rather than click, so the menu is gone before
 *   the press lands on whatever was underneath it.
 *
 * [tooltip] repeats [label] as a `title` for an icon-only trigger whose purpose is not obvious from
 * its glyph (the account shield).
 *
 * [triggerText] draws words beside the trigger's glyph, for a menu whose trigger says what is chosen
 * now ("Audible store: United States"); [label] must then contain them, so the name a voice command
 * uses is the one on screen. [trailingIcon] draws the glyph after the words instead ("More ▾").
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
    trailingIcon: Boolean = false,
    /** Drawn in the trigger in place of [icon] — the account menu's avatar. [label] still names it. */
    triggerContent: (@Composable () -> Unit)? = null,
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
            attr(ATTR_ARIA_LABEL, label)
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
            MenuTriggerFace(icon = icon, text = triggerText, content = triggerContent, trailingIcon = trailingIcon)
        }

        if (open) {
            // Choosing an item hands focus back to the trigger, as the WAI-ARIA menu button does:
            // left alone, a keyboard reader who picked a sort was dropped on <body>. An item that
            // opens a dialog still wins, since the dialog takes focus after this.
            MenuPanel(
                items = items,
                label = label,
                anchor = { trigger.element?.let { MenuAnchor.of(it) } },
                onChosen = { close(returnFocus = true) },
            )
        }
    }
}

/**
 * What a menu's trigger shows: [content] when given (an avatar), else [icon]; and [text] if any. The glyph comes first,
 * or after the words when [trailingIcon] ("More ▾").
 */
@Composable
private fun MenuTriggerFace(
    icon: WebIcon,
    text: String?,
    content: (@Composable () -> Unit)?,
    trailingIcon: Boolean,
) {
    val glyph: @Composable () -> Unit = { if (content != null) content() else Icon(icon, size = MENU_ICON_SIZE) }
    if (!trailingIcon) glyph()
    text?.let { Text(it) }
    if (trailingIcon) glyph()
}

/** Calls [onOutside] for a press anywhere outside [anchor], for as long as this is composed. */
@Composable
internal fun CloseOnOutsidePress(
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
 * The open menu: real menu-item buttons, focus on the first, the arrows walking the rest. Shared by [PopupMenu], which
 * hangs it from its trigger, and [ContextMenuHost], which hangs it from a pointer or a card.
 *
 * ⛔ It opens in the TOP LAYER (`popover`), placed against [trigger]'s on-screen box — never as an
 * absolutely-positioned child of the row it belongs to. The admin inbox's row menu was exactly that,
 * and its panel clips (`overflow: clip`): below a lone book, all that showed of the menu was a
 * sliver of its top edge. Any card, scroller or transformed box does the same, and no z-index
 * escapes a clip, so the menu leaves them all instead. Its DOM stays inside the anchor, so the
 * outside-press test, Escape and Tab still find it there.
 *
 * It hangs below its [anchor] from its left edge — right for the account menu at the content's left —
 * and measures itself as it opens: a menu that would cross the viewport's right edge (a trailing
 * "more actions" at the end of a row, on a phone) hangs from the trigger's right edge instead, and
 * one with no room below opens above. Measured rather than guessed from where the trigger sits,
 * because only the rendered menu knows how big its labels made it. It follows the trigger as
 * anything scrolls, since a fixed box would otherwise stay put while the row moves away.
 */
@Composable
internal fun MenuPanel(
    items: List<MenuAction>,
    label: String,
    anchor: () -> MenuAnchor?,
    onChosen: () -> Unit,
    /** Escape and Tab on the menu itself, for a host with no wrapping anchor to catch them ([ContextMenuHost]). */
    onKeyClose: ((returnFocus: Boolean) -> Unit)? = null,
    menuNode: NodeHolder = remember { NodeHolder() },
) {
    val lastFocused = remember { NodeHolder() }
    Div(attrs = {
        classes("menu")
        attr("role", "menu")
        attr(ATTR_ARIA_LABEL, label)
        if (onKeyClose != null) {
            onKeyDown { event ->
                when (menuKeyAction(event.key)) {
                    MenuKeyAction.CloseAndReturn -> {
                        event.preventDefault()
                        onKeyClose(true)
                    }

                    MenuKeyAction.CloseAndMoveOn -> {
                        onKeyClose(false)
                    }

                    null -> {
                        Unit
                    }
                }
            }
        }
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
                anchor()?.let { placeAgainst(menu, it) }
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
            val danger = run.all { it.value.destructive }
            if (group == null && !danger) {
                run.forEach { (index, item) -> MenuItem(item, index, items.size, menuNode, onChosen) }
            } else {
                Div(attrs = {
                    attr("role", "group")
                    attr(ATTR_ARIA_LABEL, group ?: DESTRUCTIVE_GROUP_LABEL)
                    if (danger) classes("menu-g-danger")
                }) {
                    // The group's name is already its label; the heading is for the eye. A destructive run needs none:
                    // its divider and its colour say enough (spec §2.7).
                    if (!danger && group != null) {
                        Span(attrs = {
                            classes("menu-h")
                            attr("aria-hidden", "true")
                        }) { Text(group) }
                    }
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

/**
 * [items] cut into runs of consecutive items sharing a [MenuAction.group] (null is a run too) and whether they are
 * [MenuAction.destructive], so a destructive item never shares a run with one that is not.
 */
private fun runsByGroup(items: List<IndexedValue<MenuAction>>): List<List<IndexedValue<MenuAction>>> {
    val runs = mutableListOf<MutableList<IndexedValue<MenuAction>>>()
    items.forEach { entry ->
        val last = runs.lastOrNull()
        if (last != null &&
            last.first().value.group == entry.value.group &&
            last.first().value.destructive == entry.value.destructive
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
    if (item.href != null) {
        MenuLinkItem(item, item.href, index, count, menu, onChosen)
        return
    }
    // ⛔ A real <button role="menuitem">: a clickable <div> is unreachable by keyboard and
    // announces nothing.
    Button(attrs = {
        classes("menu-i")
        if (item.destructive) classes("is-danger")
        if (!item.enabled) attr("disabled", "")
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
        item.detailedName()?.let { attr(ATTR_ARIA_LABEL, it) }
        item.keyShortcut?.let { attr("aria-keyshortcuts", it) }
        if (item.detail != null) classes("has-d")
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
            if (item.enabled) {
                if (!item.keepsOpen) onChosen()
                item.onSelect()
            }
        }
    }) {
        MenuItemFace(item)
    }
}

/** What an item shows: its glyph, its label, and its [MenuAction.detail] under the label when it has one. */
@Composable
private fun MenuItemFace(item: MenuAction) {
    item.icon?.let { Icon(it, size = MENU_ICON_SIZE) }
    val detail = item.detail
    if (detail == null) {
        Text(item.label)
    } else {
        Span(attrs = { classes("menu-i-tx") }) {
            Span { Text(item.label) }
            Span(attrs = { classes("menu-i-d") }) { Text(detail) }
        }
    }
    // Hidden from assistive tech: aria-keyshortcuts already says it, and the name stays the label.
    item.keyShortcut?.let { key ->
        Span(attrs = {
            classes("menu-i-k")
            attr("aria-hidden", "true")
        }) { Text(key) }
    }
}

/** The accessible name an item with a [MenuAction.detail] carries: "Michael Kramer, 14 books". */
private fun MenuAction.detailedName(): String? = detail?.let { "$label, $it" }

/**
 * A menu item that is a link ([MenuAction.href]): a real `<a role="menuitem">`, so a new tab and a document's own
 * tab work, and a plain click is routed in-app the way [AppLink] routes one.
 */
@Composable
private fun MenuLinkItem(
    item: MenuAction,
    href: String,
    index: Int,
    count: Int,
    menu: NodeHolder,
    onChosen: () -> Unit,
) {
    A(href = href, attrs = {
        classes("menu-i")
        if (item.destructive) classes("is-danger")
        attr("role", "menuitem")
        attr("tabindex", if (index == 0) "0" else "-1")
        item.detailedName()?.let { attr(ATTR_ARIA_LABEL, it) }
        if (item.detail != null) classes("has-d")
        if (item.opensNewTab) {
            attr("target", "_blank")
            attr("rel", "noopener")
        }
        onKeyDown { event ->
            rovingTarget(event.key, index, count, RovingAxis.Vertical)?.let { next ->
                event.preventDefault()
                (menu.element?.querySelectorAll("[role^=menuitem]")?.item(next) as? HTMLElement)?.focus()
            }
        }
        onClick { event ->
            if (item.opensNewTab) {
                onChosen()
            } else if (event.isPlainPrimaryClick()) {
                event.preventDefault()
                onChosen()
                item.onSelect()
            }
        }
    }) {
        MenuItemFace(item)
    }
}

/** What a key does to an open menu, beyond the arrows its items handle themselves. */
internal enum class MenuKeyAction {
    /** Escape: close, and hand focus back to the trigger. */
    CloseAndReturn,

    /** Tab: close, and let focus move on as Tab would anyway. */
    CloseAndMoveOn,
}

internal fun menuKeyAction(key: String): MenuKeyAction? =
    when (key) {
        "Escape" -> MenuKeyAction.CloseAndReturn
        "Tab" -> MenuKeyAction.CloseAndMoveOn
        else -> null
    }

/** A DOM node captured by `ref`, for the places a menu has to reach past composition. */
internal class NodeHolder {
    var element: HTMLElement? = null
}

private const val MENU_ICON_SIZE = 18

/** A destructive run's accessible name: it draws no heading, but a group is still named for a screen reader. */
private const val DESTRUCTIVE_GROUP_LABEL = "Delete"

private const val ATTR_ARIA_LABEL = "aria-label"
