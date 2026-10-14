package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PLAYBACK_DROP_GRACE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Fails a listen whose stream has stopped moving, at the same boundary every other platform fails a dead connection.
 *
 * A network that silently drops packets gives the browser nothing to report: hls.js and the element keep retrying
 * for about 30 s before either says a word, while Media3 on Android errors out and the shared grace
 * ([PLAYBACK_DROP_GRACE]) shows the failure at 10 s. So the web engine watches for itself. A stall that lands no bytes
 * and moves no clock for a whole [window] calls [onNoProgress]; any progress (a fragment loaded, the playhead moving)
 * starts the window again, so a stream that is slow but still arriving never fails.
 *
 * [stalled] arms the watch and leaves a running one alone (the element reports `waiting` again between fragments),
 * [progressed] restarts a running one and arms nothing, and [stop] ends it (audio moving, a pause, an error, a
 * release).
 */
internal class PlaybackStallWatch(
    private val scope: CoroutineScope,
    private val window: Duration = PLAYBACK_DROP_GRACE,
    private val onNoProgress: () -> Unit,
) {
    private var timer: Job? = null

    /** The stream stalled during a listen. */
    fun stalled() {
        if (timer?.isActive != true) arm()
    }

    /** Bytes arrived, or the playhead moved: the stream is alive, however slowly. */
    fun progressed() {
        if (timer?.isActive == true) arm()
    }

    /** The stall is over, one way or another. */
    fun stop() {
        timer?.cancel()
        timer = null
    }

    private fun arm() {
        timer?.cancel()
        timer =
            scope.launch {
                delay(window)
                timer = null
                onNoProgress()
            }
    }
}
