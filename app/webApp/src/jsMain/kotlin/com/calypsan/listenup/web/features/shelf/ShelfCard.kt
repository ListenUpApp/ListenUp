package com.calypsan.listenup.web.features.shelf

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.web.design.CoverMosaic
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.seconds

private const val LOCK_ICON_SIZE = 13

/**
 * One of the reader's shelves as a card: the cover mosaic, the name (with a lock when private) and "6 books · 74h".
 * Home's My shelves and the `/shelves` list draw the same card.
 */
@Composable
internal fun ShelfCard(
    shelf: Shelf,
    onOpenShelf: (String) -> Unit,
) {
    Button(attrs = {
        classes("home-shelf")
        attr("type", "button")
        onClick { onOpenShelf(shelf.idString) }
    }) {
        CoverMosaic(shelf.covers)
        Span(attrs = { classes("home-shelf-text") }) {
            Span(attrs = { classes("home-shelf-t") }) {
                Text(shelf.name)
                if (shelf.isPrivate) {
                    Span(attrs = {
                        classes("home-shelf-lock")
                        attr("role", "img")
                        attr("aria-label", "Private")
                    }) { Icon(WebIcon.Lock, size = LOCK_ICON_SIZE) }
                }
            }
            Span(attrs = { classes("home-shelf-sub") }) { Text(shelfMeta(shelf)) }
        }
    }
}

/** "6 books · 74h", "1 book", "1 book · 31h" (spec §2.7). */
internal fun shelfMeta(shelf: Shelf): String {
    val count = if (shelf.bookCount == 1) "1 book" else "${shelf.bookCount} books"
    return if (shelf.totalDurationSeconds > 0) {
        "$count · ${DurationFormatter.hoursMinutesCompact(shelf.totalDurationSeconds.seconds)}"
    } else {
        count
    }
}
