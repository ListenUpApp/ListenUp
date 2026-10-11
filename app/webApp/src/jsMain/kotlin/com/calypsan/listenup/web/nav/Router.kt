package com.calypsan.listenup.web.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.PopStateEvent
import org.w3c.dom.events.Event

/**
 * One addressable location in the web app: path segments plus the query map.
 *
 * The URL is a contract, not component state — Foundations: nothing that changes what you see is
 * allowed to hide in component state. `/book/42?tab=chapters&sel=9,10` names the book, the pane
 * and the selection, so the link can be shared and the page reconstructed from it.
 */
class Route(
    val segments: List<String>,
    val query: Map<String, String> = emptyMap(),
) {
    /** Serializes back to a path-and-query string, keeping the readable characters readable. */
    fun toUrl(): String {
        val path = "/" + segments.joinToString("/") { encodeReadable(it) }
        if (query.isEmpty()) return path
        val search =
            query.entries.joinToString("&") { (key, value) ->
                "${encodeReadable(key)}=${encodeReadable(value)}"
            }
        return "$path?$search"
    }

    companion object {
        /** Parses a `pathname?search` string as produced by [toUrl] or read from the location. */
        fun parse(url: String): Route {
            val path = url.substringBefore('?')
            val search = url.substringAfter('?', missingDelimiterValue = "")
            val segments =
                path
                    .split('/')
                    .filter { it.isNotEmpty() }
                    .map { decodeOrRaw(it) }
            val query =
                search
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate { parameter ->
                        val key = decodeOrRaw(parameter.substringBefore('='))
                        val value = decodeOrRaw(parameter.substringAfter('=', ""))
                        key to value
                    }
            return Route(segments, query)
        }
    }
}

/** What moved the router to its current route; the shell decides what the scrollport does from it. */
enum class RouteChange {
    /** A new page was opened: it starts at the top. */
    Push,

    /** The same page was rewritten in place (a filter, a selection): the scroll stays where it is. */
    Replace,

    /** The reader went Back or Forward: the page returns to the offset it was left at. */
    Pop,
}

/**
 * A page's claim that leaving it would lose work: the router asks it before any way out.
 *
 * @property isHeld whether there is unsaved work right now. Read live at the moment of leaving.
 * @property isAsking whether the page is already showing its question — a second Back press while
 *   the dialog is open must not stack a second question.
 * @property ask raises the page's question; [ask]'s `proceed` completes the departure when the
 *   reader chooses to discard, and is simply never called when they choose to keep editing.
 */
class UnsavedWorkHold(
    val isHeld: () -> Boolean,
    val isAsking: () -> Boolean,
    val ask: (proceed: () -> Unit) -> Unit,
)

/**
 * The history-backed router.
 *
 * [navigate] pushes an entry — moving between pages grows history so Back walks pages.
 * [replace] rewrites the current entry — filter and selection changes stay on the same entry so
 * Back leaves the page rather than unwinding every filter click.
 *
 * [beforeRouteChange] runs before [current] moves, on every path that moves it — a link, a
 * breadcrumb, or the Back button. It exists so a caller can read the outgoing page while it is
 * still on screen; Compose renders on a later frame, so this is the last moment its layout is
 * measurable. The shared-element flight uses it to learn where the cover it must fly back to is.
 *
 * A page holding unsaved work registers an [UnsavedWorkHold] ([holdUnsavedWork]); [navigate] then
 * asks it before moving, and so does the browser's Back and Forward. A popstate cannot be vetoed —
 * the URL has already changed — so the held page's URL is pushed back on top and, only if the reader
 * chooses to discard, history is walked one step back to where they were headed.
 *
 * Each history entry remembers how far its page was scrolled when it was left, in `history.state`,
 * so Back can return there. The router cannot see the DOM, so whoever owns the scrollport tells it
 * where to read the offset from ([scrollOffset]) and acts on [lastChange] and [restoredScroll].
 */
class Router(
    private val beforeRouteChange: () -> Unit = {},
) {
    private var unsavedWork: UnsavedWorkHold? = null

    /** Popstates the held page vetoed and corrected, so a discard can walk back over all of them. */
    private var vetoedPops = 0

    /** Set for the one popstate a discard causes on purpose, so it is not vetoed in turn. */
    private var letNextPopThrough = false

    private val onPopstate: (Event) -> Unit = { event ->
        val hold = unsavedWork?.takeIf { it.isHeld() && !letNextPopThrough }
        letNextPopThrough = false
        if (hold != null) {
            vetoPop(hold)
        } else {
            vetoedPops = 0
            beforeRouteChange()
            restoredScroll = (event as? PopStateEvent)?.state?.let { scrollOffsetOf(it) } ?: 0.0
            lastChange = RouteChange.Pop
            current = locationRoute()
        }
    }

    /** Puts the held page's own URL back on top of history, then asks whether to go where Back went. */
    private fun vetoPop(hold: UnsavedWorkHold) {
        vetoedPops = if (hold.isAsking()) vetoedPops + 1 else 1
        window.history.pushState(scrollState(scrollOffset()), "", current.toUrl())
        val steps = vetoedPops
        hold.ask {
            letNextPopThrough = true
            window.history.go(-steps)
        }
    }

    /** Reads the live scroll offset of the page being left. Installed by the shell that owns the scrollport. */
    var scrollOffset: () -> Double = { 0.0 }

    /** What produced [current]. Written before [current], so an effect keyed on [current] sees it. */
    var lastChange: RouteChange = RouteChange.Replace
        private set

    /** For a [RouteChange.Pop]: the offset the entry now shown was left at, or `0.0` if it never recorded one. */
    var restoredScroll: Double = 0.0
        private set

    /** The route the window currently shows; observable from composition. */
    var current: Route by mutableStateOf(locationRoute())
        private set

    init {
        window.addEventListener("popstate", onPopstate)
    }

    /**
     * Registers [hold] as the page that must be asked before the reader leaves; returns the call that
     * releases it. One page holds at a time: a later registration replaces an earlier one, and a
     * release only clears the hold it registered.
     */
    fun holdUnsavedWork(hold: UnsavedWorkHold): () -> Unit {
        unsavedWork = hold
        return { if (unsavedWork === hold) unsavedWork = null }
    }

    /**
     * Pushes [route] onto history and makes it current — after the held page, if any, agrees.
     *
     * @param force skip the question. For the page's own departures — it saved, or the reader already
     *   chose to discard — where asking again would be asking the same thing twice.
     */
    fun navigate(
        route: Route,
        force: Boolean = false,
    ) {
        val hold = unsavedWork?.takeIf { !force && it.isHeld() }
        if (hold != null) {
            hold.ask { navigate(route, force = true) }
            return
        }
        beforeRouteChange()
        window.history.replaceState(scrollState(scrollOffset()), "")
        window.history.pushState(null, "", route.toUrl())
        lastChange = RouteChange.Push
        current = route
    }

    /** Replaces the current history entry with [route]. */
    fun replace(route: Route) {
        beforeRouteChange()
        // Keep what the entry remembered: replacing the URL must not forget where the page was.
        window.history.replaceState(window.history.state, "", route.toUrl())
        lastChange = RouteChange.Replace
        current = route
    }

    /** Detaches the popstate listener; the router stops following history. */
    fun dispose() {
        window.removeEventListener("popstate", onPopstate)
    }
}

private fun scrollState(offset: Double): dynamic {
    val state = js("({})")
    state.scroll = offset
    return state
}

private fun scrollOffsetOf(state: dynamic): Double? = state.scroll as? Double

private fun locationRoute(): Route = Route.parse(window.location.pathname + window.location.search)

/**
 * Standard URL encoding, with the comma restored afterwards: `sel=9,10` is part of the page
 * contract and is meant to be read by people, not rendered as `sel=9%2C10`.
 */
private fun encodeReadable(value: String): String = encodeURIComponent(value).replace("%2C", ",")

/**
 * Percent-decodes [value], or returns it unchanged when it is not valid percent-encoding.
 *
 * `decodeURIComponent` throws `URIError` on a lone `%`, and this codec runs on whatever text is in
 * the address bar — including a link somebody hand-edited. A route that shows the raw segment is
 * wrong in a way the reader can see and fix; a thrown `URIError` out of the boot coroutine is a
 * white page.
 *
 * `URIError` is a JS error type with no Kotlin class, so `Throwable` is the narrowest catch
 * available here. No `CancellationException` re-throw clause: [Route.parse] is not `suspend`, so a
 * cancellation cannot be raised by this call.
 */
private fun decodeOrRaw(value: String): String =
    try {
        decodeURIComponent(value)
    } catch (ignored: Throwable) {
        value
    }

private external fun encodeURIComponent(value: String): String

private external fun decodeURIComponent(value: String): String
