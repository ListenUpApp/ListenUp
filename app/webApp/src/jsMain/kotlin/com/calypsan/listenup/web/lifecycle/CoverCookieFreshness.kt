package com.calypsan.listenup.web.lifecycle

import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Keeps the cover cookie's access token usable while the tab is on screen.
 *
 * Every `<img>` cover authenticates with the `listenup_access` cookie, which the DOM sends on its
 * own; it is rewritten only when a token is saved. No request of ours ever sees a cover 401, so
 * nothing on-demand can notice the token behind the cookie expiring — the shared audio-token
 * provider's 5-minute loop used to refresh it as a side effect, and that loop is gone because it
 * rotated the session from background wakes. An `<img>` error carries no status either, so a
 * retry-on-error would rotate for every genuinely missing cover.
 *
 * So the check runs on the tab's own clock, and only while it is VISIBLE: foreground, where a
 * refresh cannot be frozen or killed mid-flight, which is exactly the rule the rest of the client now
 * follows. [ensureFresh] refreshes only when the held token is near expiry (the provider's own
 * usable-or-refresh check), so a visible tab rotates about once per access-token lifetime and a
 * hidden one never. Coming back to the tab checks at once, before the first cover on screen asks.
 *
 * @return a disposer that stops the watch.
 */
internal fun keepCoverCookieFreshWhileVisible(
    ensureFresh: suspend () -> Unit,
    isVisible: () -> Boolean,
    scope: CoroutineScope,
    checkEvery: Duration = 1.minutes,
): () -> Unit {
    suspend fun check() {
        try {
            ensureFresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never fatal: the next check, or the tab's next return, tries again.
            console.warn("Could not refresh the cover cookie: ${e.message}")
        }
    }

    val clock =
        scope.launch {
            while (isActive) {
                delay(checkEvery)
                if (isVisible()) check()
            }
        }
    val onVisibility: (Event) -> Unit = { if (isVisible()) scope.launch { check() } }
    document.addEventListener(VISIBILITY_CHANGE_EVENT, onVisibility)
    return {
        clock.cancel()
        document.removeEventListener(VISIBILITY_CHANGE_EVENT, onVisibility)
    }
}

private const val VISIBILITY_CHANGE_EVENT = "visibilitychange"
