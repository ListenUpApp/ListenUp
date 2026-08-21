package com.calypsan.listenup.web.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
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
                    .map { decodeURIComponent(it) }
            val query =
                search
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate { parameter ->
                        val key = decodeURIComponent(parameter.substringBefore('='))
                        val value = decodeURIComponent(parameter.substringAfter('=', ""))
                        key to value
                    }
            return Route(segments, query)
        }
    }
}

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
 */
class Router(
    private val beforeRouteChange: () -> Unit = {},
) {
    private val onPopstate: (Event) -> Unit = {
        beforeRouteChange()
        current = locationRoute()
    }

    /** The route the window currently shows; observable from composition. */
    var current: Route by mutableStateOf(locationRoute())
        private set

    init {
        window.addEventListener("popstate", onPopstate)
    }

    /** Pushes [route] onto history and makes it current. */
    fun navigate(route: Route) {
        beforeRouteChange()
        window.history.pushState(null, "", route.toUrl())
        current = route
    }

    /** Replaces the current history entry with [route]. */
    fun replace(route: Route) {
        beforeRouteChange()
        window.history.replaceState(null, "", route.toUrl())
        current = route
    }

    /** Detaches the popstate listener; the router stops following history. */
    fun dispose() {
        window.removeEventListener("popstate", onPopstate)
    }
}

private fun locationRoute(): Route = Route.parse(window.location.pathname + window.location.search)

/**
 * Standard URL encoding, with the comma restored afterwards: `sel=9,10` is part of the page
 * contract and is meant to be read by people, not rendered as `sel=9%2C10`.
 */
private fun encodeReadable(value: String): String = encodeURIComponent(value).replace("%2C", ",")

private external fun encodeURIComponent(value: String): String

private external fun decodeURIComponent(value: String): String
