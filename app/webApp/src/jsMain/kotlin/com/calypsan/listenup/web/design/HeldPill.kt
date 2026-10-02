package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private const val HELD_ICON_SIZE = 14

/**
 * The *Held* marker: a book held for review in the admin inbox, hidden from every member.
 *
 * Static — a label, not a control — so it is its own class rather than an interactive `.pill`.
 * --warn ink on --warn-soft, the tray glyph and the word: never colour alone. `role="img"` with the
 * full sentence as its name, so a screen reader hears "Held for review, hidden from all members"
 * rather than "Held". Distinct from the collection-visibility marker, which uses neither amber nor
 * the tray.
 */
@Composable
fun HeldPill() {
    Span(attrs = {
        classes("held-pill")
        attr("role", "img")
        attr("aria-label", "Held for review, hidden from all members")
    }) {
        Icon(WebIcon.Inbox, size = HELD_ICON_SIZE)
        Text("Held")
    }
}
