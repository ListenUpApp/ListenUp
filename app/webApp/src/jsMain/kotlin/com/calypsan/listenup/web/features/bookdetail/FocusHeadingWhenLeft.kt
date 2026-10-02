package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.calypsan.listenup.web.nav.awaitAnimationFrame
import com.calypsan.listenup.web.nav.focusWithoutScroll
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Hands focus to the page's H1 when the book leaves a state whose block held the focused control.
 *
 * The held panel holds Release, and the stranded block holds "Show to all members" and "Add to a
 * collection": each succeeds by unmounting the very control that had focus, and the browser drops
 * focus to `<body>` — a screen reader says nothing, and the next Tab starts from the top of the
 * document. Landing on the heading is the house pattern for "the page under you changed" (see
 * `FocusPageOnNavigation`), and the heading is the one thing on the page that names what you are
 * now looking at — the same book, in its new state.
 *
 * It acts only on the in → out edge of [isIn], a frame later (a closing dialog or picker hands
 * focus back first), and only when focus really was lost: a reader who has already moved on keeps
 * their place.
 */
@Composable
internal fun FocusHeadingWhenLeft(
    bookId: String,
    isIn: Boolean,
    page: () -> HTMLElement?,
) {
    val memory = remember(bookId) { StateMemory(wasIn = isIn) }
    LaunchedEffect(bookId, isIn) {
        val left = memory.wasIn && !isIn
        memory.wasIn = isIn
        if (!left) return@LaunchedEffect
        awaitAnimationFrame()
        val active = document.activeElement
        if (active != null && active != document.body) return@LaunchedEffect
        val heading = page()?.querySelector(".bd-head h1") as? HTMLElement ?: return@LaunchedEffect
        focusWithoutScroll(heading)
    }
}

/** Plain holder rather than state: remembering the last value must not itself recompose. */
private class StateMemory(
    var wasIn: Boolean,
)

/** The page's own root, read lazily by [FocusHeadingWhenLeft] — never a document-wide query. */
internal class PageRoot {
    var element: HTMLElement? = null
}
