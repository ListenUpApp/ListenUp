package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.alt
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

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
 */
@Composable
fun Shell(
    sections: List<NavSection>,
    active: String,
    collapsed: Boolean = false,
    footer: List<NavEntry> = emptyList(),
    onToggleCollapse: (() -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Div(attrs = { classes("shell") }) {
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
                        Button(attrs = {
                            classes("iconbtn", "sb-toggle")
                            attr("title", "Collapse sidebar")
                            onClick { toggle() }
                        }) {
                            Icon(WebIcon.PanelLeft, size = BRAND_ICON_SIZE)
                        }
                    }
                }
            }

            sections.forEach { section ->
                section.label?.let { label ->
                    Div(attrs = { classes("sb-group") }) { Text(label) }
                }
                Nav(attrs = { classes("sb-nav") }) {
                    section.entries.forEach { entry ->
                        NavItem(entry, active, onNavigate)
                    }
                }
            }

            Div(attrs = { classes("sb-spacer") }) {}

            if (footer.isNotEmpty()) {
                Nav(attrs = { classes("sb-nav") }) {
                    footer.forEach { entry -> NavItem(entry, active, onNavigate) }
                }
            }

            if (collapsed) {
                onToggleCollapse?.let { toggle ->
                    Button(attrs = {
                        classes("iconbtn", "sb-expand")
                        attr("title", "Expand sidebar")
                        onClick { toggle() }
                    }) {
                        Icon(WebIcon.ChevronRight, size = EXPAND_ICON_SIZE)
                    }
                }
            }
        }

        Main(attrs = { classes("shell-main") }) { content() }
    }
}

@Composable
private fun NavItem(
    entry: NavEntry,
    active: String,
    onNavigate: ((String) -> Unit)?,
) {
    Div(attrs = {
        classes("nav-i")
        if (entry.key == active) classes("on")
        // In the rail forms the label survives as a tooltip; harmless when it is visible.
        attr("title", entry.label)
        onNavigate?.let { navigate -> onClick { navigate(entry.key) } }
    }) {
        Icon(entry.icon, size = NAV_ICON_SIZE)
        Span(attrs = { classes("lb") }) { Text(entry.label) }
        if (entry.badge > 0) {
            // The number is inside the control's accessible name already (the `title` above names
            // the destination), so this is decoration for a fact stated once — but a count nobody
            // reads out is a count a screen-reader user does not have. `aria-label` on the badge
            // itself says the quantity in words.
            Span(attrs = {
                classes("nav-badge")
                attr("aria-label", badgeLabel(entry.badge))
            }) { Text(badgeText(entry.badge)) }
        }
    }
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
