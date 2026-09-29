package com.calypsan.listenup.web.shell

import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.LocalScrollport
import com.calypsan.listenup.web.design.Scrollport
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.nav.FocusPageOnNavigation
import org.jetbrains.compose.web.attributes.alt
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import androidx.compose.web.events.SyntheticMouseEvent
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event

/**
 * One sidebar destination. The [key] doubles as the URL path segment, which is why it — not an
 * index — is what [Shell] reports on selection.
 */
class NavEntry(
    val key: String,
    val label: String,
    val icon: WebIcon,
    /**
     * How many things are waiting behind this entry. Zero draws nothing.
     *
     * On the entry rather than in a component of its own because the count has to survive the
     * collapsed rail, where the label is gone and the icon is all that is left — a badge parked
     * beside the label would disappear exactly when it is the only thing still saying there is
     * something to look at.
     */
    val badge: Int = 0,
    /**
     * Where the entry lives, as a real URL. Defaults to `/[key]`; Home is the one that differs, since
     * it is the root rather than `/home`.
     *
     * Rendered as the link's `href`, so the item is a genuine link: reachable by Tab, announced as a
     * link, and openable in a new tab — none of which the `<div onClick>` it replaced could be.
     */
    val href: String = "/$key",
)

/**
 * A group of sidebar destinations. [label] is the small uppercase group heading ("Yours");
 * the primary group goes unlabeled.
 */
class NavSection(
    val entries: List<NavEntry>,
    val label: String? = null,
)

/**
 * The authenticated app chrome — the design project's Shell A: a labeled, collapsible sidebar
 * beside a content region that scrolls. The persistent player bar docks below both once playback
 * exists on this platform; the layout already reserves that structure.
 *
 * Collapse is a chrome preference, not page state, so it is hoisted rather than owned here — the
 * URL contract stays about what the page shows.
 *
 * The rail form has ONE mechanism: everything renders and CSS hides the labels, so the manual
 * `.clpsd` class and the narrow-viewport media query (< 1280px forces the rail) can share it.
 * Below 1280 the toggle affordances disappear too — the rail is not a preference there.
 *
 * **Below 760 the same `<aside>` is a bottom tab bar.** The primary sections become the tabs; the
 * [footer] entries fold behind a "More" disclosure that only exists at that width, because four
 * destinations plus three more do not fit a 320px row at a thumb's size, and the ones in the footer
 * (notifications, admin, settings) are the ones a listener visits rather than lives in. One DOM for
 * every width: the landmarks, the links and the skip link are the same elements whichever form CSS
 * gives them, so a reader who resizes never lands in a different document.
 */
@Composable
fun Shell(
    sections: List<NavSection>,
    active: String,
    collapsed: Boolean = false,
    footer: List<NavEntry> = emptyList(),
    onToggleCollapse: (() -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    pageKey: String? = null,
    content: @Composable () -> Unit,
) {
    val main = remember { MainHolder() }
    val scrollport = remember { Scrollport() }
    val sidebar = remember { MainHolder() }
    pageKey?.let { key -> FocusPageOnNavigation(key) { main.element } }
    // Keyed on the page: arriving somewhere new puts More away, whichever way the reader got there.
    var moreOpen by remember(active) { mutableStateOf(false) }
    if (moreOpen) CloseOnOutsidePress(sidebar) { moreOpen = false }

    Div(attrs = { classes("shell") }) {
        // First in the document order, so it is the first Tab stop: without it a keyboard reader
        // walks eight sidebar links before every page. Visually hidden until focused. Handled in
        // script rather than left to the fragment: following `#main-content` would put a hash in the
        // URL the router owns, and does not move focus into a non-focusable `<main>` anyway.
        A(href = "#$MAIN_CONTENT_ID", attrs = {
            classes("skip-link")
            onClick { event ->
                event.preventDefault()
                main.element?.let(::focusContent)
            }
        }) { Text("Skip to content") }

        Aside(attrs = {
            classes("sidebar")
            if (collapsed) classes("clpsd")
            if (moreOpen) classes("more-open")
            ref { element ->
                sidebar.element = element
                onDispose { sidebar.element = null }
            }
            onKeyDown { event ->
                if (moreOpen && event.key == "Escape") {
                    event.preventDefault()
                    moreOpen = false
                    (sidebar.element?.querySelector(".sb-more") as? HTMLElement)?.focus()
                }
            }
        }) {
            Div(attrs = { classes("sb-brand") }) {
                Div(attrs = { classes("sb-lockup") }) {
                    // The shipped asset is a STACKED lockup (mark over wordmark), which does not fit
                    // a short horizontal rail — so the rail pairs the mark with live text instead,
                    // which also keeps the wordmark crisp at 18px and in the app's own face.
                    // `alt` is empty on purpose: the adjacent text already names the brand, and a
                    // description here would have a screen reader announce "ListenUp" twice.
                    Img(
                        src = BRAND_MARK_SRC,
                        attrs = {
                            classes("sb-mark")
                            alt("")
                        },
                    )
                    B(attrs = { classes("sb-name") }) { Text("ListenUp") }
                }
                if (!collapsed) {
                    onToggleCollapse?.let { toggle ->
                        Button(
                            kind = ButtonKind.Icon,
                            size = ButtonSize.Sm,
                            onClick = { toggle() },
                            label = "Collapse sidebar",
                            attrs = {
                                classes("sb-toggle")
                                // `title` is a hover tooltip, not a name: a screen reader may never read
                                // it, and an icon-only button with nothing else announces as "button".
                            },
                        ) {
                            Icon(WebIcon.PanelLeft, size = BRAND_ICON_SIZE)
                        }
                    }
                }
            }

            sections.forEach { section ->
                section.label?.let { label ->
                    Div(attrs = { classes("sb-group") }) { Text(label) }
                }
                Nav(attrs = {
                    classes("sb-nav")
                    attr(ARIA_LABEL, section.label ?: "Main")
                    // How many shares of the phone tab bar this section's tabs take — one each, the
                    // same one More takes, so every tab is the same width whatever the split.
                    style { property("--span", section.entries.size) }
                }) {
                    section.entries.forEach { entry ->
                        NavItem(entry, active, onNavigate) { moreOpen = false }
                    }
                }
            }

            Div(attrs = { classes("sb-spacer") }) {}

            if (footer.isNotEmpty()) {
                MoreTab(
                    footer = footer,
                    active = active,
                    open = moreOpen,
                    onToggle = { moreOpen = !moreOpen },
                )
                Nav(attrs = {
                    classes("sb-nav", "sb-foot")
                    id(MORE_MENU_ID)
                    attr(ARIA_LABEL, "Account")
                }) {
                    footer.forEach { entry -> NavItem(entry, active, onNavigate) { moreOpen = false } }
                }
            }

            if (collapsed) {
                onToggleCollapse?.let { toggle ->
                    Button(
                        kind = ButtonKind.Icon,
                        size = ButtonSize.Sm,
                        onClick = { toggle() },
                        label = "Expand sidebar",
                        attrs = {
                            classes("sb-expand")
                        },
                    ) {
                        Icon(WebIcon.ChevronRight, size = EXPAND_ICON_SIZE)
                    }
                }
            }
        }

        Main(attrs = {
            classes("shell-main")
            id(MAIN_CONTENT_ID)
            ref { element ->
                main.element = element
                scrollport.element = element
                onDispose {
                    main.element = null
                    scrollport.element = null
                }
            }
        }) {
            // The region pages scroll in, handed down so a long list can window against it.
            CompositionLocalProvider(LocalScrollport provides scrollport) { content() }
        }
    }
}

/** Where "Skip to content" lands. Focusable by script only, so it never becomes a Tab stop. */
private fun focusContent(main: HTMLElement) {
    if (!main.hasAttribute("tabindex")) main.setAttribute("tabindex", "-1")
    main.focus()
}

/** What the phone's More tab reveals, named so the button can say which region it controls. */
private const val MORE_MENU_ID = "sb-more-menu"

/** The id "Skip to content" points at. */
const val MAIN_CONTENT_ID = "main-content"

/** A live element (`<main>`, the bar), held without being state: reading it must not recompose. */
private class MainHolder {
    var element: HTMLElement? = null
}

/**
 * The phone tab bar's last tab: the [footer] destinations, one tap away.
 *
 * A disclosure button rather than a link, because it goes nowhere by itself — it reveals the links
 * that do, which stay real `<a href>` elements with `aria-current` exactly as in the sidebar. It
 * carries the footer's unread count (a badge folded into a closed menu is a badge nobody sees), and
 * lights up when the current page is one of the entries it hides, so the bar still says where you
 * are. `display:none` at every width but a phone's: the sidebar and the rail show the footer itself.
 */
@Composable
private fun MoreTab(
    footer: List<NavEntry>,
    active: String,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val unread = footer.sumOf { it.badge }
    Button(attrs = {
        classes("sb-more")
        if (footer.any { it.key == active }) classes("on")
        attr("type", "button")
        attr("aria-expanded", open.toString())
        attr("aria-controls", MORE_MENU_ID)
        onClick { onToggle() }
    }) {
        Icon(WebIcon.More, size = NAV_ICON_SIZE)
        Span(attrs = { classes("lb") }) { Text("More") }
        if (unread > 0) NavBadge(unread)
    }
}

/** Puts More away when a press lands anywhere outside the bar — the same contract as every menu. */
@Composable
private fun CloseOnOutsidePress(
    bar: MainHolder,
    onOutside: () -> Unit,
) {
    DisposableEffect(Unit) {
        // The bar's own document, not the global one: they differ when the shell renders in a frame.
        val owner = bar.element?.ownerDocument
        val onPointerDown: (Event) -> Unit = { event ->
            val target = event.target as? Node
            if (target == null || bar.element?.contains(target) != true) onOutside()
        }
        owner?.addEventListener("pointerdown", onPointerDown)
        onDispose { owner?.removeEventListener("pointerdown", onPointerDown) }
    }
}

@Composable
private fun NavItem(
    entry: NavEntry,
    active: String,
    onNavigate: ((String) -> Unit)?,
    /** Called on every activation, plain or modified — the phone's More puts itself away on it. */
    onFollow: () -> Unit = {},
) {
    val isActive = entry.key == active
    A(href = entry.href, attrs = {
        classes("nav-i")
        if (isActive) {
            classes("on")
            attr("aria-current", "page")
        }
        // In the rail forms the label survives as a tooltip; harmless when it is visible.
        attr("title", entry.label)
        onClick { event ->
            onFollow()
            // A modified or non-primary click is the reader asking the browser for a new tab or
            // window, so it keeps its default. Only a plain click is routed in-app.
            onNavigate?.let { navigate ->
                if (event.isPlainPrimaryClick()) {
                    event.preventDefault()
                    navigate(entry.key)
                }
            }
        }
    }) {
        Icon(entry.icon, size = NAV_ICON_SIZE)
        Span(attrs = { classes("lb") }) { Text(entry.label) }
        if (entry.badge > 0) NavBadge(entry.badge)
    }
}

/**
 * An unread count on a nav control.
 *
 * The number is inside the control's accessible name already (the `title` names the destination),
 * so this is decoration for a fact stated once — but a count nobody reads out is a count a
 * screen-reader user does not have. `aria-label` on the badge itself says the quantity in words.
 */
@Composable
private fun NavBadge(count: Int) {
    Span(attrs = {
        classes("nav-badge")
        attr(ARIA_LABEL, badgeLabel(count))
    }) { Text(badgeText(count)) }
}

/** A click the app routes itself; anything modified is the reader asking the browser for a new tab. */
internal fun SyntheticMouseEvent.isPlainPrimaryClick(): Boolean =
    button == PRIMARY_BUTTON && !ctrlKey && !metaKey && !shiftKey && !altKey

private const val PRIMARY_BUTTON: Short = 0

/**
 * The brand mark, served from `web/public/`.
 *
 * A crop of the shipped `listenup_logo_black.svg` down to the mark alone — same vector art the
 * Android and iOS brand assets use, so the rail cannot drift from the other clients' logo.
 */
private const val BRAND_MARK_SRC = "/listenup-mark.svg"

/** "9" through "99", then "99+" — a three-digit badge stops being a number and becomes a smear. */
private fun badgeText(count: Int): String = if (count > BADGE_MAX) "$BADGE_MAX+" else count.toString()

/** What a screen reader says instead of reading "99+" as characters. */
private fun badgeLabel(count: Int): String =
    if (count == 1) {
        "1 unread"
    } else if (count > BADGE_MAX) {
        "more than $BADGE_MAX unread"
    } else {
        "$count unread"
    }

private const val BADGE_MAX = 99

private const val NAV_ICON_SIZE = 21

private const val BRAND_ICON_SIZE = 19

private const val EXPAND_ICON_SIZE = 18

private const val ARIA_LABEL = "aria-label"
