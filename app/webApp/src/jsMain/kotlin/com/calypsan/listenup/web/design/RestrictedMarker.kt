package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import org.jetbrains.compose.web.dom.Span

/**
 * The ids of the books an admin should see locked — `RestrictedBooksViewModel`'s set, provided once
 * around the shell by `WebAppRoot`. Empty by default (a member, a spec without a provider).
 */
val LocalRestrictedBookIds = compositionLocalOf<Set<String>> { emptySet() }

/** The lock's accessible name, word for word the Android and iOS one (`library.restricted_a11y`). */
internal const val RESTRICTED_LABEL = "In a collection, so only people it is shared with can see it."

/**
 * The collection-visibility lock: the selection tick's vocabulary — a dark-glass disc, a white ring,
 * a white lock — so it reads the same on any cover art in either theme. Drawn in a [Cover]'s
 * `overlay`, absolutely positioned, so it never changes a card's height. A fact, not a control:
 * `role="img"` with the sentence as its name (and as a hover `title`), never in the tab order.
 *
 * Distinct from the inbox's *Held* pill, which no restricted book ever wears.
 *
 * @param compact For row covers of 56px and under.
 * @param shifted In library selection mode, clear of the selection tick.
 */
@Composable
fun RestrictedMarker(
    bookId: String,
    compact: Boolean = false,
    shifted: Boolean = false,
) {
    if (bookId !in LocalRestrictedBookIds.current) return
    Span(attrs = {
        classes("lu-lock")
        if (compact) classes("sm")
        if (shifted) classes("shifted")
        attr("role", "img")
        attr("aria-label", RESTRICTED_LABEL)
        attr("title", RESTRICTED_LABEL)
    }) {
        Icon(WebIcon.Lock, size = if (compact) COMPACT_ICON else FULL_ICON)
    }
}

private const val FULL_ICON = 13

private const val COMPACT_ICON = 10
