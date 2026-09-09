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
    document.addEventListener(VISIBILITY_CHANGE, onVisibility)
    window.addEventListener("pagehide", onPageHide)
    return {
        document.removeEventListener(VISIBILITY_CHANGE, onVisibility)
        window.removeEventListener("pagehide", onPageHide)
    }
}

/**
 * Re-opens a sync firehose that died while the tab was away, and returns the disposer.
 *
 * `SyncRepository.connectRealtime`'s own KDoc names the callers on every other platform —
 * "MainActivity.onResume, the auth-transition collector, shell entry". A browser has only the
 * auth transition, which fires once, so a laptop that slept came back with a dead firehose and
 * no gesture that could revive it. `recoverRealtime` no-ops the reconnect when the connection
 * is already healthy (`SyncEngine.recoverRealtime`), so a tab switch costs nothing.
 *
 * The `online` edge matters independently: `SyncEngine` drops an outbox drain while offline
 * and waits for a later edge to pick it up. This is that edge.
 *
 * @param recover how a dead firehose gets re-opened.
 * @param isVisible how to read the document's visibility. A parameter so a spec can drive it.
 * @param scope the lifetime the recover runs on.
 */
internal fun recoverSyncOnReturn(
    recover: suspend () -> Unit,
    isVisible: () -> Boolean,
    scope: CoroutineScope,
): () -> Unit {
    fun run() {
        scope.launch {
            try {
                recover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same reasoning as the auth-transition collector in Main.kt: a sync failure must
                // never take the tab down, because everything already in Room still works.
                console.warn("Failed to recover realtime sync: ${e.message}")
            }
        }
    }

    val onVisibility: (Event) -> Unit = { if (isVisible()) run() }
    val onOnline: (Event) -> Unit = { run() }
    document.addEventListener(VISIBILITY_CHANGE, onVisibility)
    window.addEventListener("online", onOnline)
    return {
        document.removeEventListener(VISIBILITY_CHANGE, onVisibility)
        window.removeEventListener("online", onOnline)
    }
}

/** The one edge both registrations share: the tab changing from watched to not, or back. */
private const val VISIBILITY_CHANGE = "visibilitychange"
