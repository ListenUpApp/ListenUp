package com.calypsan.listenup.client.playback

import androidx.media3.common.C
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline

/**
 * Decides the start index/position for a controller `setMediaItems` request — the seam behind
 * [PlaybackService]'s `onSetMediaItems` override (see its KDoc for the full contract).
 *
 * - An explicit [requestedStartIndex] (`!= C.INDEX_UNSET`) is the in-app
 *   `AndroidPlaybackController.setMediaQueue` path: pass both values through verbatim.
 * - Otherwise, when the request contained exactly one item and it resolved to a book —
 *   the Auto tap-to-play / voice-search shape — resume at the book's saved position via
 *   [PlaybackTimeline.resolve]. `resumePositionMs` already includes the auto-rewind offset
 *   (folded in by `PlaybackPreparer.resumeStartPositionMs`). This is the #1236 pin.
 * - Otherwise `C.INDEX_UNSET`/`C.TIME_UNSET`, which tells Media3 to apply its own defaults.
 */
internal fun autoStartPosition(
    requestedStartIndex: Int,
    requestedStartPositionMs: Long,
    requestItemCount: Int,
    resumeTimeline: PlaybackTimeline?,
    resumePositionMs: Long,
): Pair<Int, Long> =
    when {
        requestedStartIndex != C.INDEX_UNSET -> {
            requestedStartIndex to requestedStartPositionMs
        }

        requestItemCount == 1 && resumeTimeline != null -> {
            resumeTimeline.resolve(resumePositionMs).let { it.mediaItemIndex to it.positionInFileMs }
        }

        else -> {
            C.INDEX_UNSET to C.TIME_UNSET
        }
    }
