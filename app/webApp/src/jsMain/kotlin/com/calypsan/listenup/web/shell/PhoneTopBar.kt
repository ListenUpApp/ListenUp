package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.isPlainPrimaryClick
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The phone's header (spec §3.1.7 L1/L33, and the Home/Discover shell): the page's name, then Search, sync,
 * notifications and the account, on one row of 44px targets, as Android and iOS draw theirs. Absent at ≥ 760px, where
 * the sidebar carries all four.
 *
 * [title] is decoration over the page's own `h1`, which a phone keeps for screen readers but hides from sight (see
 * `06-shell.css`), so a page never has two headings. Null leaves the row to its controls — Home's greeting is its own
 * title.
 *
 * Search and Notifications are real links (Tab, a new tab, "link"), routed in-app on a plain click like the tab bar.
 */
@Composable
fun PhoneTopBar(
    title: String?,
    unread: Int,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    sync: @Composable () -> Unit,
    account: @Composable () -> Unit,
) {
    Header(attrs = { classes("ptb") }) {
        Span(attrs = {
            classes("ptb-t")
            attr("aria-hidden", "true")
        }) { title?.let { Text(it) } }
        Div(attrs = { classes("ptb-acts") }) {
            BarLink(href = SEARCH_HREF, label = "Search", icon = WebIcon.Search, onFollow = onSearch)
            sync()
            BarLink(
                href = NOTIFICATIONS_HREF,
                label = if (unread > 0) "Notifications, $unread unread" else "Notifications",
                icon = WebIcon.Bell,
                onFollow = onNotifications,
            ) {
                if (unread > 0) {
                    Span(attrs = {
                        classes("ptb-badge")
                        attr("aria-hidden", "true")
                    }) { Text(if (unread > BADGE_MAX) "$BADGE_MAX+" else unread.toString()) }
                }
            }
            account()
        }
    }
}

@Composable
private fun BarLink(
    href: String,
    label: String,
    icon: WebIcon,
    onFollow: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    A(href = href, attrs = {
        classes("ptb-b")
        attr("aria-label", label)
        attr("title", label)
        onClick { event ->
            if (event.isPlainPrimaryClick()) {
                event.preventDefault()
                onFollow()
            }
        }
    }) {
        Icon(icon, size = ICON_SIZE)
        extra()
    }
}

private const val SEARCH_HREF = "/search"
private const val NOTIFICATIONS_HREF = "/notifications"
private const val ICON_SIZE = 21
private const val BADGE_MAX = 99
