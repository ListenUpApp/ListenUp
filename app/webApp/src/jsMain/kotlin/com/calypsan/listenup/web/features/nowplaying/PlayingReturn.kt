package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Where Now Playing closes to, and who gets focus back (S4, R7). Opened from inside the app, closing is history back
 * and focus returns to the control that opened it; reached by a deep link, closing replaces the entry with Home, since
 * there is nothing inside the app to go back to. Arriving by the browser's Forward also counts as a deep link: closing
 * then goes Home, which never leaves the app.
 */
class PlayingReturn {
    private var opener: HTMLElement? = null
    private var openedInApp = false

    /** Go to `/playing` from [from], remembering it for focus return. */
    fun open(
        router: Router,
        from: HTMLElement?,
    ) {
        opener = from
        openedInApp = true
        router.navigate(Routes.playing)
    }

    /** Close the page: back when it was opened in the app, else Home. Focus returns to the opener when it is still there. */
    fun close(router: Router) {
        val returnTo = opener
        opener = null
        if (openedInApp) {
            openedInApp = false
            lateinit var onPop: (Event) -> Unit
            onPop = {
                window.removeEventListener("popstate", onPop)
                window.requestAnimationFrame { returnTo?.takeIf { it.isConnected }?.focus() }
            }
            window.addEventListener("popstate", onPop)
            window.history.back()
        } else {
            router.replace(Routes.home)
        }
    }
}
