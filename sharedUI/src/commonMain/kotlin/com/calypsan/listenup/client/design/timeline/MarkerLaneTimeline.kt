package com.calypsan.listenup.client.design.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    }
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
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingMs by remember { mutableStateOf(0L) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .onSizeChanged { state.viewportWidthPx = it.width.toFloat() }
                .pointerInput(lane, ghosts) {
                    if (ghosts != null) return@pointerInput // negotiation suspended in ghost-preview mode
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val geometry = state.geometryFor(durationMs)
                            val hitMs = geometry.pxToMs(offset.x)
                            val target = markers.minByOrNull { kotlin.math.abs(it.timeMs - hitMs) }
                            if (target != null && negotiator.beginDrag(target)) {
                                draggingId = target.id
                                draggingMs = target.timeMs
                                haptics.thresholdActivate()
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            val id = draggingId ?: return@detectHorizontalDragGestures
                            val geometry = state.geometryFor(durationMs)
                            val proposedMs = geometry.pxToMs(change.position.x)
                            val siblings = markers.filterNot { it.id == id }
                            val update = negotiator.dragTo(proposedMs, siblings) ?: return@detectHorizontalDragGestures
                            if (update.resisted) haptics.thresholdActivate() else haptics.selectionTick()
                            draggingMs = update.ms
                        },
                        onDragEnd = {
                            negotiator.endDrag(draggingMs)
                            haptics.commit()
                            draggingId = null
                        },
                        onDragCancel = {
                            negotiator.cancelDrag()
                            draggingId = null
                        },
                    )
                }.pointerInput(lane) {
                    detectTapGestures { /* tap-to-select future hook; no-op for v1 */ }
                },
    ) {
        markers.forEach { marker ->
            val geometry = state.geometryFor(durationMs)
            val ms = if (marker.id == draggingId) draggingMs else marker.timeMs
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
    }
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
