package com.calypsan.listenup.web.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume

/**
 * Moves keyboard focus to the new page's H1 whenever the reader navigates to a different page.
 *
 * A single-page app swaps the content and leaves focus wherever it was: on a sidebar link, or, when
 * the clicked card was unmounted with the old page, on `<body>`. A screen reader then announces
 * nothing, and the next Tab starts from the top of the document. Landing on the heading says where
 * you are and starts Tab from the page you came to (WCAG 2.4.3).
 *
 * [pageKey] is the route's PATH, never its query: `?tab=`, `?sort=` and a typed search are the same
 * page rearranging itself, and pulling focus off the control that just did it would be hostile.
 *
 * It stands down:
 * - on first load, where the browser's own start-of-document focus is right;
 * - while a `<dialog>` is open, because a modal owns focus until it closes;
 * - when the reader has already moved focus somewhere else during the wait;
 * - when the control that navigated is still on screen inside the page (an in-page chip or tab),
 *   which is a control rearranging its own page, not a journey somewhere new.
 *
 * The page's H1 can arrive a few frames late (a book loading from the local database), so it is
 * polled for briefly. If none comes, the content region itself takes focus, which still restarts
 * Tab at the page rather than at the top of the chrome. For the same window it keeps watching the
 * heading it focused, and follows it if a loading page's header gives way to its hero's.
 *
 * [content] is the shell's own `<main>`, read lazily: a document-wide query would find whichever
 * shell happened to be first in the document, not this one.
 */
@Composable
fun FocusPageOnNavigation(
    pageKey: String,
    content: () -> HTMLElement?,
) {
    val memory = remember { PageMemory() }
    LaunchedEffect(pageKey) {
        val previous = memory.lastKey
        memory.lastKey = pageKey
        if (previous == null || previous == pageKey) return@LaunchedEffect

        val origin = document.activeElement
        var focused: HTMLElement? = null
        repeat(HEADING_WAIT_FRAMES) {
            awaitAnimationFrame()
            if (dialogIsOpen()) return@LaunchedEffect
            val main = content() ?: return@LaunchedEffect
            val landed = focused
            if (landed != null) {
                focused = followReplacedHeading(landed, main)
                return@repeat
            }
            if (!focusIsStill(origin)) return@LaunchedEffect
            if (origin != null && origin != document.body && main.contains(origin)) return@LaunchedEffect
            focused = (main.querySelector("h1") as? HTMLElement)?.also(::focusWithoutScroll)
        }
        if (focused == null && focusIsStill(origin) && !dialogIsOpen()) content()?.let(::focusWithoutScroll)
    }
}

/**
 * A loading page names itself with a pending header, then its hero renders the real one somewhere
 * else in the tree. The heading focus landed on is then gone, and focus with it; this carries focus
 * to the heading that replaced it. Returns the heading that now holds focus.
 */
private fun followReplacedHeading(
    landed: HTMLElement,
    main: HTMLElement,
): HTMLElement {
    if (landed.isConnected || !focusIsStill(landed)) return landed
    val replacement = main.querySelector("h1") as? HTMLElement ?: return landed
    focusWithoutScroll(replacement)
    return replacement
}

/** Plain holder rather than state: remembering the last key must not itself cause a recomposition. */
private class PageMemory {
    var lastKey: String? = null
}

/**
 * Whether focus is where navigation left it — the element that navigated, or nowhere in particular
 * because that element went away with the old page.
 */
private fun focusIsStill(origin: Element?): Boolean {
    val active = document.activeElement
    return active == null || active == document.body || active == origin
}

private fun dialogIsOpen(): Boolean = document.querySelector("dialog[open]") != null

/**
 * `tabindex=-1` makes a heading focusable by script without adding it to the Tab order.
 * `preventScroll`, because the shell already restores the page's own scroll position and a focus
 * jump would fight it.
 */
private fun focusWithoutScroll(element: HTMLElement) {
    if (!element.hasAttribute("tabindex")) element.setAttribute("tabindex", "-1")
    val options = js("{}")
    options.preventScroll = true
    element.asDynamic().focus(options)
}

private suspend fun awaitAnimationFrame() {
    suspendCancellableCoroutine { continuation ->
        window.requestAnimationFrame { continuation.resume(Unit) }
    }
}

/** About half a second at 60fps: long enough for a local read, short enough to never surprise. */
private const val HEADING_WAIT_FRAMES = 30
