package com.calypsan.listenup.web.lifecycle

import com.calypsan.listenup.core.BookId
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event

/** Where the listener is, right now: the two values a durable save needs. */
internal data class Playhead(
    val bookId: BookId,
    val positionMs: Long,
)

/**
 * Writes the listener's place down immediately.
 *
 * A function type rather than a `ProgressTracker` parameter: the tracker takes four
 * collaborators to construct, and the only thing this hook needs from it is one call.
 */
internal fun interface PositionFlush {
    suspend fun flush(playhead: Playhead)
}

/**
 * Saves the playhead the moment the tab stops being watched, and returns the disposer.
 *
 * Position is written on a ten-second timer and on explicit pause, so closing a tab
 * mid-chapter loses everything since the last tick. Android has had the equivalent since
 * `PlaybackService.onTaskRemoved`; this is the browser's.
 *
 * ⛔ `visibilitychange → hidden` is the PRIMARY hook, not `pagehide`. The write goes to Room
 * through the SQLite web worker — a `postMessage` round-trip — and a page that is already
 * unloading may not live long enough to complete it. `visibilitychange` fires while the tab
 * is fully alive (a tab switch, a minimise, a phone reaching the home screen), which is both
 * earlier and far more common than a close. `pagehide` is kept as a last chance; the save is
 * idempotent, so firing twice costs one redundant write.
 *
 * No `navigator.sendBeacon`: the save is a local Room write plus an outbox enqueue, in one
 * transaction (`PlaybackPositionRepositoryImpl.savePlaybackState`). There is no HTTP request
 * to smuggle out of an unloading page.
 *
 * @param playhead where playback is, or null when nothing is loaded.
 * @param flush how the place gets written down.
 * @param isHidden how to read the document's visibility. A parameter so a spec can drive it —
 *   a real browser tab under test is always visible.
 * @param scope the lifetime the save runs on.
 */
internal fun flushPositionWhenHidden(
    playhead: () -> Playhead?,
    flush: PositionFlush,
    isHidden: () -> Boolean,
    scope: CoroutineScope,
): () -> Unit {
    fun save() {
        val now = playhead() ?: return
        scope.launch {
            try {
                flush.flush(now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                console.warn("Could not save the playback position on tab hide: ${e.message}")
            }
        }
    }

    val onVisibility: (Event) -> Unit = { if (isHidden()) save() }
    val onPageHide: (Event) -> Unit = { save() }
    document.addEventListener("visibilitychange", onVisibility)
    window.addEventListener("pagehide", onPageHide)
    return {
        document.removeEventListener("visibilitychange", onVisibility)
        window.removeEventListener("pagehide", onPageHide)
    }
}
