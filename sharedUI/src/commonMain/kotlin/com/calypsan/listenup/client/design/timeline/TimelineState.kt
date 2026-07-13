package com.calypsan.listenup.client.design.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * Caller-owned zoom/pan/viewport state for a [MarkerLaneTimeline], surviving configuration change.
 * `durationMs` is supplied fresh on every call from the caller's data, not stored here — only the
 * user's interaction state (zoom, pan, viewport measurement) is retained.
 */
@Stable
class TimelineState {
    var zoom by mutableFloatStateOf(TimelineGeometry.MIN_ZOOM)
        internal set

    var panMs by mutableLongStateOf(0L)
        internal set

    var viewportWidthPx by mutableFloatStateOf(0f)
        internal set

    internal fun geometryFor(durationMs: Long) =
        TimelineGeometry(
            durationMs = durationMs,
            viewportWidthPx = viewportWidthPx,
            zoom = zoom,
            panMs = panMs,
        )

    internal fun applyGeometry(geometry: TimelineGeometry) {
        zoom = geometry.zoom
        panMs = geometry.panMs
    }

    companion object {
        val Saver: Saver<TimelineState, *> =
            Saver(
                save = { listOf(it.zoom, it.panMs) },
                restore = { saved ->
                    TimelineState().apply {
                        zoom = saved[0] as Float
                        panMs = saved[1] as Long
                    }
                },
            )
    }
}

/** Remembers a [TimelineState], surviving configuration change via [rememberSaveable]. */
@Composable
fun rememberTimelineState(): TimelineState = rememberSaveable(saver = TimelineState.Saver) { TimelineState() }

/** Visual tokens for one [TimeMarker.styleKey] — resolved by the caller from the theme, never hardcoded here. */
data class MarkerStyle(
    val color: Color,
    val shape: Shape,
)
