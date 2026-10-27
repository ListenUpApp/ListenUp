package com.calypsan.listenup.web.shell

import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.isPlainPrimaryClick
import com.calypsan.listenup.web.design.LocalScrollport
import com.calypsan.listenup.web.design.Scrollport
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.nav.FocusPageOnNavigation
import org.jetbrains.compose.web.attributes.alt
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

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
    /** What [badge] counts; decides its colour and what a screen reader hears. */
    val badgeKind: NavBadgeKind = NavBadgeKind.Unread,
    /**
     * Where the entry lives, as a real URL. Defaults to `/[key]`; Home is the one that differs, since
     * it is the root rather than `/home`.
     *
     * Rendered as the link's `href`, so the item is a genuine link: reachable by Tab, announced as a
     * link, and openable in a new tab — none of which the `<div onClick>` it replaced could be.
     */
    val href: String = "/$key",
    /** Whether the phone's tab bar shows this entry; false for one the phone header carries instead (Search). */
    val onPhone: Boolean = true,
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
 * What a nav badge counts. [Unread] is the bell's coral "act here"; [Held] is the Library's amber
 * "waiting for you" — books held for review. Different words to a screen reader, different colour to
 * the eye, so a waiting book never reads as an unread notification.
 */
enum class NavBadgeKind { Unread, Held }

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
 * **Below 760 the same `<aside>` is a bottom tab bar** of Home · Library · Discover (spec §3.1.7 L33), and [topBar]
 * is the phone's header: the page's name, Search, sync, notifications and the account, whose menu takes the [footer]
 * entries the bar has no room for. One DOM for every width: the landmarks, the links and the skip link are the same
 * elements whichever form CSS gives them, so a reader who resizes never lands in a different document.
 *
 * [sidebarStatus] sits above the footer in the full sidebar (the sync line); the rail and the phone hide it.
 * [titled] says the phone header is showing the page's name, so the page's own heading steps out of sight there.
 * [immersive] is a page that takes the whole phone screen (Now Playing, S4): below 760px the tabs, the header and the
 * player bar step aside; wider, nothing changes. [morphClasses] are the player morph's, while it runs.
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
    topBar: (@Composable () -> Unit)? = null,
    titled: Boolean = false,
    sidebarStatus: (@Composable () -> Unit)? = null,
    immersive: Boolean = false,
    morphClasses: List<String> = emptyList(),
    content: @Composable () -> Unit,
) {
    val main = remember { MainHolder() }
    val scrollport = remember { Scrollport() }
    pageKey?.let { key -> FocusPageOnNavigation(key) { main.element } }

    Div(attrs = {
        classes("shell")
        if (titled) classes("ptb-titled")
        if (immersive) classes("shell-immersive")
        classes(morphClasses)
    }) {
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
                    section.entries.forEach { entry -> NavItem(entry, active, onNavigate) }
                }
            }

            Div(attrs = { classes("sb-spacer") }) {}

            sidebarStatus?.let { status -> Div(attrs = { classes("sb-status") }) { status() } }

            if (footer.isNotEmpty()) {
                Nav(attrs = {
                    classes("sb-nav", "sb-foot")
                    attr(ARIA_LABEL, "Account")
                }) {
                    footer.forEach { entry -> NavItem(entry, active, onNavigate) }
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
            topBar?.invoke()
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

/** The id "Skip to content" points at. */
const val MAIN_CONTENT_ID = "main-content"

/** A live element (`<main>`, the bar), held without being state: reading it must not recompose. */
private class MainHolder {
    var element: HTMLElement? = null
}

@Composable
private fun NavItem(
    entry: NavEntry,
    active: String,
    onNavigate: ((String) -> Unit)?,
) {
    val isActive = entry.key == active
    A(href = entry.href, attrs = {
        classes("nav-i")
        if (!entry.onPhone) classes("not-phone")
        if (isActive) {
            classes("on")
            attr("aria-current", "page")
        }
        // In the rail forms the label survives as a tooltip; harmless when it is visible.
        attr("title", entry.label)
        onClick { event ->
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
        if (entry.badge > 0) NavBadge(entry.badge, entry.badgeKind)
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
private fun NavBadge(
    count: Int,
    kind: NavBadgeKind = NavBadgeKind.Unread,
) {
    Span(attrs = {
        classes("nav-badge")
        if (kind == NavBadgeKind.Held) classes("is-held")
        attr(ARIA_LABEL, badgeLabel(count, kind))
    }) { Text(badgeText(count)) }
}

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
private fun badgeLabel(
    count: Int,
    kind: NavBadgeKind,
): String =
    when (kind) {
        NavBadgeKind.Unread -> {
            if (count == 1) {
                "1 unread"
            } else if (count > BADGE_MAX) {
                "more than $BADGE_MAX unread"
            } else {
                "$count unread"
            }
        }

        NavBadgeKind.Held -> {
            if (count == 1) {
                "1 book waiting for review"
            } else if (count > BADGE_MAX) {
                "more than $BADGE_MAX books waiting for review"
            } else {
                "$count books waiting for review"
            }
        }
    }

private const val BADGE_MAX = 99

private const val NAV_ICON_SIZE = 21

private const val BRAND_ICON_SIZE = 19

private const val EXPAND_ICON_SIZE = 18

private const val ARIA_LABEL = "aria-label"
