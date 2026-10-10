package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.main
import com.calypsan.listenup.web.motion.fadePageIn
import com.calypsan.listenup.web.motion.isPageChange
import com.calypsan.listenup.web.nav.RouteChange
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.awaitAnimationFrame
import kotlin.js.Date
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * A page change fades; a route change within one does not. `lastPage` starts null so the first
 * paint is not a fade — a library materialising out of nothing on load is motion nobody asked
 * for, and it would sit between the reader and content that has already arrived.
 */
@Composable
internal fun FadeOnPageChange(page: String) {
    var lastPage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(page) {
        if (isPageChange(lastPage, page)) {
            document.querySelector(SHELL_MAIN)?.let { fadePageIn(it) }
        }
        lastPage = page
    }
}

/**
 * Makes the shell's one scrollport follow navigation: a page the reader opens starts at the top, and
 * Back returns to the offset the page was left at.
 *
 * The scrollport never unmounts on a route change, so without this the offset simply persisted into
 * whatever page came next. A [RouteChange.Replace] (a filter, a selection) changes nothing — that is
 * the same page, and the reader is still looking at it.
 */
@Composable
internal fun ScrollFollowsNavigation(router: Router) {
    DisposableEffect(router) {
        router.scrollOffset = { (document.querySelector(SHELL_MAIN) as? HTMLElement)?.scrollTop ?: 0.0 }
        onDispose { router.scrollOffset = { 0.0 } }
    }
    val route = router.current
    LaunchedEffect(route) {
        val main = document.querySelector(SHELL_MAIN) as? HTMLElement ?: return@LaunchedEffect
        when (router.lastChange) {
            RouteChange.Push -> main.scrollTop = 0.0
            RouteChange.Replace -> Unit
            RouteChange.Pop -> restoreScroll(main, router.restoredScroll)
        }
    }
}

/**
 * Scrolls [main] to [offset], waiting for the page to grow tall enough to honour it.
 *
 * A page Back returns to is rebuilt from Room a few frames after the route moves, and a browser
 * clamps `scrollTop` to what exists, so an immediate assignment lands short. Gives up after a
 * second and leaves the clamped position rather than fighting a page that never grows.
 */
private suspend fun restoreScroll(
    main: HTMLElement,
    offset: Double,
) {
    val deadline = Date.now() + SCROLL_RESTORE_WINDOW_MS
    while (true) {
        main.scrollTop = offset
        if (main.scrollTop >= offset || Date.now() >= deadline) return
        awaitAnimationFrame()
    }
}

/** How long Back keeps waiting for a rebuilt page to be tall enough to scroll to where it was left. */
private const val SCROLL_RESTORE_WINDOW_MS = 1_000.0

/**
 * ⛔ Read once per composition, not per row. `relativeOrMonthYear` needs a now to measure against,
 * and a reader list that asked the clock for every line could render two rows either side of a
 * midnight boundary — one "today", one "yesterday", for finishes a millisecond apart.
 */
internal fun nowMs(): Long = Date.now().toLong()

/** The shell's content region — the thing a page change fades. See [fadePageIn]. */
private const val SHELL_MAIN = ".shell-main"
