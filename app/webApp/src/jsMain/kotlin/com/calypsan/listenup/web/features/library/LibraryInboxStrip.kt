package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.admin.InboxBadgeState
import com.calypsan.listenup.web.shell.isPlainPrimaryClick
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The inbox's own route — the strip is a real link to it. */
private const val INBOX_HREF = "/admin/inbox"

private const val TILE_ICON_SIZE = 22
private const val FAN_COVER_SIZE = 40
private const val FAN_COVER_RADIUS = 6
private const val FAN_COVER_RUNG = 80
private const val CHEVRON_SIZE = 18
private const val FAN_MAX = 3

/**
 * The Library's way into the admin inbox: "Inbox · 3 new books — Waiting for you to release",
 * a --warn tile, the newest held covers, "Review", and a chevron.
 *
 * Above the virtualised grid, in the slot `.lib-status.is-above-grid` uses, so no card height ever
 * changes for it. A real `<a href>` (reachable by Tab, openable in a new tab), routed in-app on a
 * plain primary click like the sidebar's links. One accessible name: "Inbox, 3 books waiting for
 * review". Absent at zero.
 */
@Composable
internal fun LibraryInboxStrip(
    inbox: InboxBadgeState,
    onOpenInbox: () -> Unit,
) {
    val count = inbox.heldCount
    if (count <= 0) return
    A(href = INBOX_HREF, attrs = {
        classes("lib-inbox")
        attr("aria-label", if (count == 1) "Inbox, 1 book waiting for review" else "Inbox, $count books waiting for review")
        onClick { event ->
            if (event.isPlainPrimaryClick()) {
                event.preventDefault()
                onOpenInbox()
            }
        }
    }) {
        Span(attrs = {
            classes("lib-inbox-tile")
            attr("aria-hidden", "true")
        }) { Icon(WebIcon.Inbox, size = TILE_ICON_SIZE) }
        Span(attrs = { classes("lib-inbox-text") }) {
            Span(attrs = { classes("lib-inbox-t") }) {
                Text(if (count == 1) "Inbox · 1 new book" else "Inbox · $count new books")
            }
            Span(attrs = { classes("lib-inbox-sub") }) { Text("Waiting for you to release") }
        }
        if (inbox.previewBookIds.isNotEmpty()) {
            Span(attrs = {
                classes("lib-inbox-fan")
                attr("aria-hidden", "true")
            }) {
                inbox.previewBookIds.take(FAN_MAX).forEach { id ->
                    Cover(
                        title = "",
                        imageUrl = coverUrl(id, null, FAN_COVER_RUNG),
                        size = FAN_COVER_SIZE,
                        radius = FAN_COVER_RADIUS,
                        decorative = true,
                        attrs = { classes("lib-inbox-cover") },
                    )
                }
            }
        }
        Span(attrs = { classes("lib-inbox-review") }) { Text("Review") }
        Span(attrs = { classes("lib-inbox-chev") }) { Icon(WebIcon.ChevronRight, size = CHEVRON_SIZE) }
    }
}
