package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.AddEventListenerOptions
import org.w3c.dom.events.Event

/**
 * Where Now Playing closes to, and who gets focus back (S4, R7). Opened from inside the app, closing is history back
 * and focus returns to the control that opened it; reached by a deep link, closing replaces the entry with Home, since
 * there is nothing inside the app to go back to. Arriving by the browser's Forward also counts as a deep link: closing
 * then goes Home, which never leaves the app.
 *
 * [morph] is told just before `/playing` is pushed, while the bar it grows from is still drawn.
 */
class PlayingReturn(
    private val morph: MorphController? = null,
) {
    private var opener: HTMLElement? = null
    private var openedInApp = false

    /**
     * True from a close until the history step it took has landed. While it is, `/playing` must not redirect on its
     * own (Close book empties the player in the same moment it goes back).
     */
    var isClosing = false
        private set

    /** Go to `/playing` from [from], remembering it for focus return. */
    fun open(
        router: Router,
        from: HTMLElement?,
    ) {
        opener = from
        openedInApp = true
        isClosing = false
        morph?.beforeOpen(router.current)
        router.navigate(Routes.playing)
    }

    /** Close the page: back when it was opened in the app, else Home. Focus returns to the opener when it is still there. */
    fun close(router: Router) {
        val returnTo = opener
        opener = null
        if (openedInApp) {
            openedInApp = false
            isClosing = true
            val onPop: (Event) -> Unit = {
                isClosing = false
                window.requestAnimationFrame { returnTo?.takeIf { it.isConnected }?.focus() }
            }
            window.addEventListener("popstate", onPop, AddEventListenerOptions(once = true))
            window.history.back()
        } else {
            router.replace(Routes.home)
        }
    }
}
