package com.calypsan.listenup.client.design.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import kotlin.time.Duration.Companion.milliseconds

/**
 * The generic marker-lane timeline: renders [lanes] top-to-bottom on a shared [durationMs]-wide
 * time axis, negotiates drags per-lane through each lane's [MarkerLane.policy] (via
 * [DragNegotiator]), and reports seeks via [onSeek]. Has ZERO knowledge of what a marker means —
 * chapter/event semantics live entirely in the caller-supplied [MarkerLane.policy] instances.
 *
 * @param playheadMs Deferred read of the current playback position (the `PlayerScrubber` perf
 *   idiom) so a fast-ticking playhead only recomposes the playhead layer, not the whole timeline.
 * @param styles Maps [TimeMarker.styleKey] to a visual token — no hardcoded colors in this file.
 * @param ghosts When non-null, drag negotiation is suspended and these render as a dimmed overlay
 *   alongside (not instead of) the live markers.
 */
@Composable
fun MarkerLaneTimeline(
    lanes: List<MarkerLane>,
    durationMs: Long,
    playheadMs: () -> Long,
    onSeek: (Long) -> Unit,
    styles: Map<String, MarkerStyle>,
    ghosts: List<TimeMarker>? = null,
    modifier: Modifier = Modifier,
    state: TimelineState = rememberTimelineState(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        lanes.forEach { lane ->
            MarkerLaneRow(
                lane = lane,
                durationMs = durationMs,
                state = state,
                styles = styles,
                ghosts = ghosts,
            )
        }
        TimelineAxis(durationMs = durationMs, playheadMs = playheadMs, onSeek = onSeek, state = state)
        TimelineMinimap(lanes = lanes, durationMs = durationMs, state = state)
    }
}

/**
 * Per-row drag scratch state: which marker (if any) is being dragged, its live position, and the
 * fine-scrub bookkeeping (accumulated px at current sensitivity, the Y where the drag started).
 * A plain class (not `@Composable`) holding Compose `State` delegates directly, mirroring
 * [TimelineState] — lets the gesture-handling extension functions below share one `remember`d
 * instance without threading five separate mutable vars through their parameter lists.
 */
private class MarkerDragState {
    var draggingId by mutableStateOf<String?>(null)
    var draggingMs by mutableStateOf(0L)
    var dragAccumulatedPx by mutableFloatStateOf(0f)
    var dragStartY by mutableFloatStateOf(0f)
}

@Composable
private fun MarkerLaneRow(
    lane: MarkerLane,
    durationMs: Long,
    state: TimelineState,
    styles: Map<String, MarkerStyle>,
    ghosts: List<TimeMarker>?,
) {
    val haptics = LocalHaptics.current
    val markers by lane.markers.collectAsState(initial = emptyList())
    val negotiator = remember(lane) { DragNegotiator(lane.policy) }
    val dragState = remember(lane) { MarkerDragState() }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .onSizeChanged { state.viewportWidthPx = it.width.toFloat() }
                .negotiatedDrag(lane, ghosts, durationMs, state, markers, negotiator, haptics, dragState)
                .pointerInput(lane) {
                    detectTapGestures { /* tap-to-select future hook; no-op for v1 */ }
                }.doubleTapZoom(lane, durationMs, state)
                .pinchZoom(lane, durationMs, state),
    ) {
        markers.forEach { marker ->
            val geometry = state.geometryFor(durationMs)
            val ms = if (marker.id == dragState.draggingId) dragState.draggingMs else marker.timeMs
            MarkerChip(
                marker = marker,
                px = geometry.msToPx(ms),
                style = styles[marker.styleKey],
                dimmed = ghosts != null,
            )
        }
        if (ghosts != null) {
            ghosts.forEach { ghost ->
                val geometry = state.geometryFor(durationMs)
                GhostMarkerChip(marker = ghost, px = geometry.msToPx(ghost.timeMs))
            }
        }
        if (dragState.draggingId != null) {
            val geometry = state.geometryFor(durationMs)
            PrecisionHud(ms = dragState.draggingMs, px = geometry.msToPx(dragState.draggingMs))
        }
    }
}

/**
 * Drag-to-reposition a marker: hit-tests the nearest marker on pickup, negotiates every move
 * through [negotiator], and applies the [TimelineGeometry.fineScrubSensitivity] vertical-precision
 * idiom before proposing each ms. No-ops entirely in ghost-preview mode ([ghosts] non-null).
 */
private fun Modifier.negotiatedDrag(
    lane: MarkerLane,
    ghosts: List<TimeMarker>?,
    durationMs: Long,
    state: TimelineState,
    markers: List<TimeMarker>,
    negotiator: DragNegotiator,
    haptics: Haptics,
    dragState: MarkerDragState,
): Modifier =
    pointerInput(lane, ghosts) {
        if (ghosts != null) return@pointerInput // negotiation suspended in ghost-preview mode
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                val geometry = state.geometryFor(durationMs)
                val hitMs = geometry.pxToMs(offset.x)
                val target = markers.minByOrNull { kotlin.math.abs(it.timeMs - hitMs) }
                if (target != null && negotiator.beginDrag(target)) {
                    dragState.draggingId = target.id
                    dragState.draggingMs = target.timeMs
                    dragState.dragAccumulatedPx = geometry.msToPx(target.timeMs)
                    dragState.dragStartY = offset.y
                    haptics.thresholdActivate()
                }
            },
            onHorizontalDrag = { change, dragAmount ->
                val id = dragState.draggingId ?: return@detectHorizontalDragGestures
                val geometry = state.geometryFor(durationMs)
                val verticalDisplacementPx = change.position.y - dragState.dragStartY
                val sensitivity = TimelineGeometry.fineScrubSensitivity(verticalDisplacementPx)
                dragState.dragAccumulatedPx += dragAmount * sensitivity
                val proposedMs = geometry.pxToMs(dragState.dragAccumulatedPx)
                val siblings = markers.filterNot { it.id == id }
                val update = negotiator.dragTo(proposedMs, siblings) ?: return@detectHorizontalDragGestures
                if (update.resisted) haptics.thresholdActivate() else haptics.selectionTick()
                dragState.draggingMs = update.ms
            },
            onDragEnd = {
                negotiator.endDrag(dragState.draggingMs)
                haptics.commit()
                dragState.draggingId = null
            },
            onDragCancel = {
                negotiator.cancelDrag()
                dragState.draggingId = null
            },
        )
    }

/** Double-tap steps zoom in (x2, capped at [TimelineGeometry.maxZoomFor]) or back out to 1x. */
private fun Modifier.doubleTapZoom(
    lane: MarkerLane,
    durationMs: Long,
    state: TimelineState,
): Modifier =
    pointerInput(lane, durationMs) {
        detectTapGestures(
            onDoubleTap = { offset ->
                val geometry = state.geometryFor(durationMs)
                val focalMs = geometry.pxToMs(offset.x)
                val atMaxZoom = geometry.zoom >= TimelineGeometry.maxZoomFor(durationMs, geometry.viewportWidthPx)
                val nextZoom = if (atMaxZoom) TimelineGeometry.MIN_ZOOM else geometry.zoom * 2f
                state.applyGeometry(geometry.zoomedAbout(nextZoom, focalMs, offset.x))
            },
        )
    }

/** Pinch-to-zoom, keeping the pinch centroid stationary via [TimelineGeometry.zoomedAbout]. */
private fun Modifier.pinchZoom(
    lane: MarkerLane,
    durationMs: Long,
    state: TimelineState,
): Modifier =
    pointerInput(lane, durationMs) {
        detectTransformGestures { centroid, _, zoom, _ ->
            if (zoom == 1f) return@detectTransformGestures
            val geometry = state.geometryFor(durationMs)
            val focalMs = geometry.pxToMs(centroid.x)
            state.applyGeometry(geometry.zoomedAbout(geometry.zoom * zoom, focalMs, centroid.x))
        }
    }

/**
 * Floating time readout shown near a marker while it's being dragged, mirroring
 * `AlphabetScrollbar`'s bubble idiom — millisecond precision so fine-scrub drags are legible.
 */
@Composable
private fun PrecisionHud(
    ms: Long,
    px: Float,
) {
    Box(
        modifier =
            Modifier
                .offset { IntOffset(px.toInt(), -40) }
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatPreciseTime(ms),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Formats [ms] as `HH:MM:SS.mmm` — the fine-scrub precision HUD's fixed format. */
private fun formatPreciseTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val hours = clamped / 3_600_000L
    val minutes = clamped % 3_600_000L / 60_000L
    val seconds = clamped % 60_000L / 1_000L
    val millis = clamped % 1_000L
    return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
}

@Composable
private fun MarkerChip(
    marker: TimeMarker,
    px: Float,
    style: MarkerStyle?,
    dimmed: Boolean,
) {
    val color = style?.color ?: MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .offset { IntOffset(px.toInt(), 0) }
                .size(12.dp)
                .clip(style?.shape ?: CircleShape)
                .background(if (dimmed) color.copy(alpha = 0.4f) else color)
                .semantics {
                    marker.label?.let { label ->
                        this.contentDescription = "$label at ${marker.timeMs.milliseconds}"
                    }
                },
    )
}

/**
 * Ghost-preview marker: rendered in [MaterialTheme.colorScheme.tertiary] (the amber-gold
 * drift/ghost accent) at full alpha, visually distinct from a dimmed live [MarkerChip].
 */
@Composable
private fun GhostMarkerChip(
    marker: TimeMarker,
    px: Float,
) {
    Box(
        modifier =
            Modifier
                .offset { IntOffset(px.toInt(), 0) }
                .size(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary)
                .semantics {
                    marker.label?.let { label ->
                        this.contentDescription = "Ghost preview: $label at ${marker.timeMs.milliseconds}"
                    }
                },
    )
}

@Composable
private fun TimelineAxis(
    durationMs: Long,
    playheadMs: () -> Long,
    onSeek: (Long) -> Unit,
    state: TimelineState,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .semantics {
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            current = playheadMs().toFloat(),
                            range = 0f..durationMs.toFloat(),
                        )
                    setProgress(label = "Seek") { target ->
                        onSeek(target.toLong().coerceIn(0L, durationMs))
                        true
                    }
                }.pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val geometry = state.geometryFor(durationMs)
                        onSeek(geometry.pxToMs(offset.x))
                    }
                },
    ) {
        val current = playheadMs()
        val geometry = state.geometryFor(durationMs)
        Box(
            modifier =
                Modifier
                    .offset { IntOffset(geometry.msToPx(current).toInt(), 0) }
                    .size(2.dp, 24.dp)
                    .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}
