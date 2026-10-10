package com.calypsan.listenup.web.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.calypsan.listenup.web.design.LocalScrollport
import com.calypsan.listenup.web.design.Scrollport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/*
 * Where the reader was, per URL, so Back can return them there.
 *
 * The shell's content region (`.shell-main`) is ONE scroller that outlives every page, so the
 * browser's own scroll restoration never applies — a route change swaps what is inside a scroller
 * that never moved. This does the browser's job by hand:
 *
 *   - Leaving: the router's hook ([captureScrollBeforeRouteChange], via [readLeavingPage]) records the
 *     outgoing page's offset while that page is still laid out — the last moment it can be read.
 *   - Arriving: [ScrollRestoration] starts a new page at the top, restores the saved offset on Back,
 *     and leaves the place alone for a change made in place (a sort, a filter, a tab).
 *
 * ⛔ Restoration WAITS for height. A virtualised list first renders a screenful, measures itself a
 * frame later and only then has its full height; setting `scrollTop` before that clamps to the
 * screenful and the reader lands near the top. So [restoreScroll] retries each frame until the page
 * can scroll that far, and gives up the moment the reader scrolls for themselves.
 *
 * Shared-element flights must measure the settled page, not one about to jump, so the hook marks a
 * restoration pending and [whenScrollSettled] holds their measurement until it is done.
 */

private val offsets = LinkedHashMap<String, Double>()

/**
 * The URL on show and the scroller it shows in, as of the last arrival. The [Scrollport], not its
 * element: on the very first paint this page's effect runs before the shell's region has handed its
 * element over, and the first page is the one a reader most often comes Back to.
 */
private var showing: Pair<String, Scrollport>? = null

private var lastChange: RouteChange? = null

private var settling = false

private val waitingForScroll = mutableListOf<() -> Unit>()

/** How the router last moved — PUSH, REPLACE, POP — or null before it ever has. */
internal fun lastRouteChange(): RouteChange? = lastChange

/**
 * The router's hook, called before the route moves: records the outgoing page's offset under its
 * URL, notes how the route is changing, and holds shared-element flights until the arrival settles.
 */
internal fun captureScrollBeforeRouteChange(change: RouteChange) {
    showing?.let { (url, scrollport) ->
        scrollport.element?.takeIf { it.isConnected }?.let { port -> rememberOffset(url, port.scrollTop) }
    }
    lastChange = change
    settling = true
}

/** The offset [route] was last left at, if it was. */
internal fun savedScrollFor(route: Route): Double? = offsets[route.toUrl()]

/** Runs [action] now, or once the current arrival has finished placing the scroll. */
internal fun whenScrollSettled(action: () -> Unit) {
    if (settling) waitingForScroll += action else action()
}

/** Marks the arrival's scroll as placed and runs everything that was waiting for it. */
internal fun settleScroll() {
    settling = false
    val ready = waitingForScroll.toList()
    waitingForScroll.clear()
    ready.forEach { it() }
}

/** Specs only: forgets every offset and pending wait. Production never calls it. */
internal fun forgetScrollMemory() {
    offsets.clear()
    showing = null
    lastChange = null
    settling = false
    waitingForScroll.clear()
}

/**
 * Places the content region's scroll for each arrival — top for a new page, the saved offset for
 * Back, untouched for a change in place. Rendered once, inside the shell, beside the page.
 *
 * Keyed on the [Route] object, not its URL: every navigation makes a new one, so a link to the page
 * already showing still counts as an arrival and still settles.
 */
@Composable
internal fun ScrollRestoration(route: Route) {
    val scrollport = LocalScrollport.current ?: return
    val shown = remember { ShownPath() }
    val scope = rememberCoroutineScope()
    DisposableEffect(route) {
        val path = route.segments.joinToString("/")
        val isNewPath = shown.path != null && shown.path != path
        shown.path = path
        showing = route.toUrl() to scrollport
        val restoring = scrollport.element?.let { port -> arrive(route, port, isNewPath, scope) }
        if (restoring == null) settleScroll()
        onDispose { restoring?.cancel() }
    }
}

/** Places the scroll for this arrival; returns the restoration still running, if one is. */
private fun arrive(
    route: Route,
    port: HTMLElement,
    isNewPath: Boolean,
    scope: CoroutineScope,
): Job? =
    when (lastChange) {
        RouteChange.POP -> {
            savedScrollFor(route)?.let { target ->
                scope.launch {
                    restoreScroll(port, target)
                    settleScroll()
                }
            }
        }

        RouteChange.PUSH -> {
            if (isNewPath) port.scrollTop = 0.0
            null
        }

        RouteChange.REPLACE, null -> {
            null
        }
    }

/**
 * Scrolls [port] to [target] as soon as the page is tall enough to go there, checking once a frame
 * for up to about a second. Stops the moment the reader scrolls for themselves: their intent beats a
 * remembered one.
 */
internal suspend fun restoreScroll(
    port: HTMLElement,
    target: Double,
) {
    var readerScrolled = false
    val readerMoved: (Event) -> Unit = { readerScrolled = true }
    READER_SCROLL_EVENTS.forEach { port.addEventListener(it, readerMoved) }
    try {
        repeat(RESTORE_WAIT_FRAMES) {
            if (readerScrolled) return
            if ((port.scrollHeight - port.clientHeight).toDouble() >= target) {
                port.scrollTop = target
                return
            }
            awaitAnimationFrame()
        }
        if (!readerScrolled) port.scrollTop = target
    } finally {
        READER_SCROLL_EVENTS.forEach { port.removeEventListener(it, readerMoved) }
    }
}

private fun rememberOffset(
    url: String,
    offset: Double,
) {
    offsets.remove(url)
    offsets[url] = offset
    if (offsets.size > MEMORY_CAPACITY) offsets.remove(offsets.keys.first())
}

/** Plain holder, not state: remembering the last path must not itself recompose anything. */
private class ShownPath {
    var path: String? = null
}

/** The reader taking the wheel: any of these during a restoration cancels it. */
private val READER_SCROLL_EVENTS = listOf("wheel", "touchstart", "keydown", "pointerdown")

/** About a second at 60 fps — long enough for a local read and a list's first measurement. */
private const val RESTORE_WAIT_FRAMES = 60

/** Pages remembered. Oldest first out; a session rarely walks back further than this. */
private const val MEMORY_CAPACITY = 50
