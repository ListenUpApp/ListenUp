package com.calypsan.listenup.client.design.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

private const val MINIMAP_BUCKET_COUNT = 48

/**
 * A slim density-shaded strip below the [MarkerLaneTimeline] axis: shows where markers cluster
 * across the whole [durationMs] regardless of current zoom, with a viewport-window indicator and
 * drag-to-pan. Aggregates every lane's markers into one density signal — the minimap has no
 * per-lane distinction, only "where are things."
 */
@Composable
fun TimelineMinimap(
    lanes: List<MarkerLane>,
    durationMs: Long,
    state: TimelineState,
    modifier: Modifier = Modifier,
) {
    val allMarkerTimes =
        lanes.flatMap { lane ->
            val markers by lane.markers.collectAsState(initial = emptyList())
            markers.map { it.timeMs }
        }
    val geometry = state.geometryFor(durationMs)
    val buckets = geometry.bucketDensity(allMarkerTimes, MINIMAP_BUCKET_COUNT)
    val maxCount = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(16.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        // The minimap spans the WHOLE duration (effectively zoom 1), while the
                        // window indicator being dragged lives in the zoomed detail lane's
                        // coordinate space — scale by the current zoom to convert minimap px into
                        // the equivalent detail-lane px before delegating to the tested pannedBy.
                        // Dragging the window right should pan forward, the mirror of dragging the
                        // detail-lane content itself, hence the extra negation.
                        state.applyGeometry(state.geometryFor(durationMs).pannedBy(-dragAmount * state.zoom))
                    }
                },
    ) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            buckets.forEach { count ->
                val alpha = if (maxCount == 0) 0f else (count.toFloat() / maxCount).coerceIn(0f, 1f)
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.8f)),
                )
            }
        }

        // Viewport-window indicator: the slice of the minimap currently visible in the detail lane.
        if (durationMs > 0 && state.viewportWidthPx > 0) {
            val density = LocalDensity.current
            val windowWidthFraction = (1f / geometry.zoom).coerceIn(0f, 1f)
            val windowStartFraction = (state.panMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val windowWidthDp = with(density) { (state.viewportWidthPx * windowWidthFraction).toDp() }
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(windowWidthDp)
                        .offset { IntOffset((state.viewportWidthPx * windowStartFraction).toInt(), 0) }
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            )
        }
    }
}
