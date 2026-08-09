package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
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
                B(attrs = { classes("sb-name") }) { Text("ListenUp") }
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
    }
}

private const val NAV_ICON_SIZE = 21

private const val BRAND_ICON_SIZE = 19

private const val EXPAND_ICON_SIZE = 18
