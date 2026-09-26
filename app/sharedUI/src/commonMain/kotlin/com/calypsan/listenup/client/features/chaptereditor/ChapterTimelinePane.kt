package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.haptics.Haptics
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.geometry.Offset
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import androidx.compose.runtime.State
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.design.timeline.ChapterDetailLane
import com.calypsan.listenup.client.design.timeline.ChapterMiniMap
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineChapter
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineFileBoundary
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineLane
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.chapterDensity
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_detail_lane
import listenup.composeapp.generated.resources.chapter_editor_lane_description
import listenup.composeapp.generated.resources.chapter_editor_minimap_description
import listenup.composeapp.generated.resources.chapter_editor_whole_book
import listenup.composeapp.generated.resources.chapter_editor_zoom_hint
import listenup.composeapp.generated.resources.chapter_editor_zoom_in
import listenup.composeapp.generated.resources.chapter_editor_zoom_out
import org.jetbrains.compose.resources.stringResource

private val TIMELINE_PANE_SHAPE = RoundedCornerShape(24.dp)
private const val MINIMAP_BUCKETS = 90

/** One zoom button press, or one wheel notch: a fifth narrower, or a quarter wider. */
private const val ZOOM_IN_STEP = 0.8f
private const val ZOOM_OUT_STEP = 1.25f

/**
 * The timeline half of the editor: the whole-book minimap over the draggable detail lane.
 *
 * Every gesture is translated into the shared [TimelineLane] and drawn from what comes back, the
 * same model iOS and web drive — so a drag previews while it moves and commits once on release
 * ([onRetime]), pulling away slows it, a pinch or the mouse wheel zooms, and a drag in open lane
 * pans. The zoom buttons are there for anyone who cannot pinch.
 *
 * @param chapters every chapter, numbered against the whole book.
 * @param lane the window and any drag in progress; [onLaneChange] receives the next one.
 * @param onRetime a drag was released: the boundary and where it lands.
 */
@Composable
internal fun ChapterTimelinePane(
    chapters: List<NumberedChapter>,
    bookDurationMs: Long,
    lane: TimelineLane,
    onLaneChange: (TimelineLane) -> Unit,
    selectedChapterId: String?,
    playheadMs: Long?,
    fileBoundaries: List<TimelineFileBoundary>,
    ghosts: List<TimelineChapter>,
    lockedChapterIds: Set<String>,
    onRetime: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val plain = chapters.map { it.chapter }
    val previewed = lane.preview(plain, bookDurationMs)
    val markers =
        chapters.mapIndexed { index, numbered ->
            TimelineChapter(
                id = numbered.chapter.id,
                number = numbered.number,
                startMs = previewed[index].startTime,
                locked = numbered.chapter.id in lockedChapterIds,
                selected = numbered.chapter.id == selectedChapterId,
            )
        }
    // Gesture callbacks outlive a recomposition; they read these as they are now, never as they
    // were when the pointer went down.
    val live =
        LiveLane(
            lane = rememberUpdatedState(lane),
            markers = rememberUpdatedState(markers),
            chapters = rememberUpdatedState(plain),
            onLaneChange = rememberUpdatedState(onLaneChange),
            onRetime = rememberUpdatedState(onRetime),
        )

    Column(
        modifier
            .clip(TIMELINE_PANE_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimelineLabel(
            leading = stringResource(Res.string.chapter_editor_whole_book),
            trailing = ChapterTimeFormat.clock(bookDurationMs),
        )
        ChapterMiniMap(
            density = chapterDensity(plain.map { it.startTime }, bookDurationMs, MINIMAP_BUCKETS),
            viewportStartFraction = fractionOf(lane.geometry.windowStartMs, bookDurationMs),
            viewportEndFraction = fractionOf(lane.geometry.windowEndMs, bookDurationMs),
            onSeekFraction = { fraction ->
                val at = (fraction.toDouble() * bookDurationMs).toLong()
                live.onLaneChange.value(live.lane.value.centredOn(at, bookDurationMs))
            },
            contentDescription = stringResource(Res.string.chapter_editor_minimap_description),
        )
        LaneHeader(onZoom = { factor -> onLaneChange(lane.zoomedAroundCentre(factor, bookDurationMs)) })
        Box {
            ChapterDetailLane(
                geometry = lane.geometry,
                chapters = markers,
                fileBoundaries = fileBoundaries,
                ghosts = ghosts,
                playheadMs = playheadMs,
                contentDescription = stringResource(Res.string.chapter_editor_lane_description, chapters.size),
                modifier = Modifier.laneGestures(live, bookDurationMs),
            )
            // The step the pull landed in AND the time the boundary would take (spec §7.2): the
            // step makes the pull aimable, the number is what the reader is aiming at.
            lane.readout(plain, bookDurationMs)?.let { readout ->
                LaneReadout(readout, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/** What the gesture handlers read: always the latest values, never the ones from when they attached. */
private class LiveLane(
    val lane: State<TimelineLane>,
    val markers: State<List<TimelineChapter>>,
    val chapters: State<List<Chapter>>,
    val onLaneChange: State<(TimelineLane) -> Unit>,
    val onRetime: State<(String, Long) -> Unit>,
)

/**
 * Every gesture the lane answers: measuring, two-finger pinch, the desktop wheel, and the one-finger
 * drag — a boundary if it lands on one, the window if it lands in open lane.
 */
@Composable
private fun Modifier.laneGestures(
    live: LiveLane,
    bookDurationMs: Long,
): Modifier {
    val localDensity = LocalDensity.current
    val haptics = LocalHaptics.current
    val zoomState =
        rememberTransformableState { zoomChange, _, _ ->
            // A pinch spreading apart (zoomChange > 1) shows less time, so the window shrinks.
            val now = live.lane.value
            live.onLaneChange.value(now.zoomed(1f / zoomChange, now.geometry.widthPx / 2f, bookDurationMs))
        }
    return this
        .onSizeChanged { live.onLaneChange.value(live.lane.value.measured(it.width.toFloat())) }
        // Two fingers zoom; one finger is left for the drag below (canPan = false).
        .transformable(zoomState, canPan = { false })
        .pointerInput(bookDurationMs) {
            // The mouse wheel on desktop: a notch is one zoom step around the pointer.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull()
                    if (event.type == PointerEventType.Scroll && change != null) {
                        val step = if (change.scrollDelta.y > 0f) ZOOM_OUT_STEP else ZOOM_IN_STEP
                        live.onLaneChange.value(live.lane.value.zoomed(step, change.position.x, bookDurationMs))
                        change.consume()
                    }
                }
            }
        }.pointerInput(bookDurationMs) {
            val drag = BoundaryDrag(live, bookDurationMs, haptics) { dy -> with(localDensity) { dy.toDp().value } }
            detectDragGestures(
                onDragStart = drag::start,
                onDragEnd = drag::end,
                onDragCancel = drag::cancel,
                onDrag = drag::move,
            )
        }
}

/**
 * One drag on the lane: a boundary if it began on one, the window if it began in open lane.
 *
 * Keeps its own working lane: several movements can arrive before a recomposition hands back the
 * updated one, and folding each into a stale copy would drop travel.
 */
private class BoundaryDrag(
    private val live: LiveLane,
    private val bookDurationMs: Long,
    private val haptics: Haptics,
    private val toDp: (Float) -> Float,
) {
    private var working = live.lane.value
    private var startY = 0f
    private var held = false

    private fun push(next: TimelineLane) {
        working = next
        live.onLaneChange.value(next)
    }

    fun start(offset: Offset) {
        startY = offset.y
        held = false
        push(live.lane.value.grabbed(offset.x, live.markers.value))
        if (working.drag != null) haptics.thresholdActivate()
    }

    fun move(
        change: PointerInputChange,
        amount: Offset,
    ) {
        if (working.drag == null) {
            // Open lane: the drag moves the window instead of a boundary.
            push(working.panned(amount.x, bookDurationMs))
            return
        }
        // Measured from where the drag began, not the lane's middle: a marker grabbed near the top
        // can only be pulled downward.
        push(working.dragged(amount.x, toDp(change.position.y - startY)))
        // The boundary resists at a neighbour rather than crossing it (spec §7.6).
        val nowHeld = working.isHeldByNeighbour(live.chapters.value, bookDurationMs)
        if (nowHeld && !held) haptics.selectionTick()
        held = nowHeld
    }

    fun end() {
        val active = working.drag
        val landing = working.committedStartMs(live.chapters.value, bookDurationMs)
        // One drag, one edit: committed here, never per movement.
        if (active != null && landing != null) live.onRetime.value(active.chapterId, landing)
        push(working.released())
    }

    fun cancel() = push(working.released())
}

/** "Detail lane", the zoom hint, and the zoom buttons for anyone who cannot pinch. */
@Composable
private fun LaneHeader(onZoom: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TimelineLabel(
            leading = stringResource(Res.string.chapter_editor_detail_lane),
            trailing = stringResource(Res.string.chapter_editor_zoom_hint),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onZoom(ZOOM_OUT_STEP) }) {
            Icon(Icons.Default.ZoomOut, stringResource(Res.string.chapter_editor_zoom_out))
        }
        IconButton(onClick = { onZoom(ZOOM_IN_STEP) }) {
            Icon(Icons.Default.ZoomIn, stringResource(Res.string.chapter_editor_zoom_in))
        }
    }
}

@Composable
private fun LaneReadout(
    readout: String,
    modifier: Modifier = Modifier,
) {
    Text(
        readout,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier =
            modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun TimelineLabel(
    leading: String,
    trailing: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = leading,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** Where [ms] sits in the book, as a `0f..1f` fraction. Zero-safe for a book with no duration. */
private fun fractionOf(
    ms: Long,
    bookDurationMs: Long,
): Float = if (bookDurationMs <= 0L) 0f else (ms.toDouble() / bookDurationMs).toFloat().coerceIn(0f, 1f)
