package com.calypsan.listenup.client.design.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val LANE_HEIGHT = 170.dp
private val MARKER_WIDTH = 2.dp
private val SELECTED_MARKER_WIDTH = 3.dp
private val PLAYHEAD_WIDTH = 2.dp
private val PLAYHEAD_ARROW = 8.dp
private val GHOST_INSET = 10.dp
private const val PEAK_HEIGHT_FRACTION = 0.62f
private const val PEAK_ALPHA = 0.5f
private const val UNSELECTED_MARKER_ALPHA = 0.55f
private const val GHOST_ALPHA = 0.9f

/** Half-width of the playhead's arrowhead, as a fraction of [PLAYHEAD_ARROW]. */
private const val ARROW_HALF_WIDTH = 0.75f
private val DASH_ON = 6.dp
private val DASH_OFF = 5.dp

/**
 * The zoomable working surface: the window of the book you are actually editing.
 *
 * Everything is positioned through [geometry], so the lane has no opinion about scale — the same
 * component draws a ten-second window and a whole 65-hour book, and simply becomes useless for
 * precision at the far end, which is true and is why the editor offers zoom and fine-scrub rather
 * than pretending otherwise.
 *
 * Four layers, back to front, in the order the eye should find them:
 *
 * 1. **Peaks** — faint, decorative, and waveform-*ready*. The spec defers real peak data to a later
 *    phase; passing an empty list draws nothing and the lane is laid out identically, so the data
 *    can arrive without a structural change.
 * 2. **File boundaries** — dashed, read-only. A fact about the media, never a thing you drag.
 * 3. **Ghosts** — where drift correction *would* move each boundary, shown before it commits.
 * 4. **Chapter markers and the playhead** — the live, editable layer.
 *
 * The lane draws; it does not gesture. Dragging a boundary belongs to the caller, which owns the
 * draft and the undo stack — this keeps the surface reusable for the drift preview, where nothing
 * is draggable at all.
 *
 * @param geometry the window and its pixel mapping.
 * @param chapters boundaries to draw; those outside the window are skipped, not clamped to the edges.
 * @param modifier Modifier for the lane.
 * @param fileBoundaries read-only audio-file dividers.
 * @param ghosts corrected positions previewed during drift, drawn dashed alongside the current ones.
 * @param peaks per-column amplitudes in `0f..1f`. Empty until waveform data exists.
 * @param playheadMs current transport position, or null when nothing is playing.
 * @param height lane height; the mobile layout draws this shorter.
 * @param contentDescription spoken summary — the lane is pure geometry and says nothing on its own.
 */
@Composable
fun ChapterDetailLane(
    geometry: TimelineGeometry,
    chapters: List<TimelineChapter>,
    modifier: Modifier = Modifier,
    fileBoundaries: List<TimelineFileBoundary> = emptyList(),
    ghosts: List<TimelineChapter> = emptyList(),
    peaks: List<Float> = emptyList(),
    playheadMs: Long? = null,
    height: Dp = LANE_HEIGHT,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = MaterialTheme.colorScheme
    val peakColor = colors.surfaceContainerHighest
    val dividerColor = colors.outline
    val markerColor = colors.onSurfaceVariant
    val selectedColor = colors.primary
    val ghostColor = colors.tertiary
    val playheadColor = colors.tertiary

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, shape)
            .then(
                if (contentDescription == null) {
                    Modifier
                } else {
                    Modifier.semantics { this.contentDescription = contentDescription }
                },
            ),
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (size.width <= 0f) return@Canvas
            val lane = geometry.copy(widthPx = size.width)

            drawPeaks(peaks, peakColor)
            fileBoundaries.forEach { drawDashedDivider(lane.xOf(it.startMs), dividerColor) }
            ghosts.forEach { drawGhost(lane.xOf(it.startMs), ghostColor) }
            chapters.forEach { chapter ->
                drawMarker(
                    x = lane.xOf(chapter.startMs),
                    color = if (chapter.selected) selectedColor else markerColor,
                    widthPx = (if (chapter.selected) SELECTED_MARKER_WIDTH else MARKER_WIDTH).toPx(),
                    alpha = if (chapter.selected) 1f else UNSELECTED_MARKER_ALPHA,
                )
            }
            playheadMs?.let { drawPlayhead(lane.xOf(it), playheadColor) }
        }
    }
}

/** Faint amplitude columns behind everything else. Empty until real waveform data lands. */
private fun DrawScope.drawPeaks(
    peaks: List<Float>,
    color: Color,
) {
    if (peaks.isEmpty()) return
    val slot = size.width / peaks.size
    val width = (slot - 1.5f).coerceAtLeast(1f)
    peaks.forEachIndexed { i, p ->
        val h = size.height * PEAK_HEIGHT_FRACTION * p.coerceIn(0f, 1f)
        drawRoundRect(
            color = color.copy(alpha = PEAK_ALPHA),
            topLeft = Offset(i * slot, (size.height - h) / 2f),
            size = Size(width, h),
            cornerRadius = CornerRadius(2f, 2f),
        )
    }
}

/** A read-only audio-file boundary. Dashed, because it is reference and not a handle. */
private fun DrawScope.drawDashedDivider(
    x: Float,
    color: Color,
) {
    if (x < 0f || x > size.width) return
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx())),
    )
}

/** Where drift would put this boundary — inset top and bottom so it reads as a preview, not a peer. */
private fun DrawScope.drawGhost(
    x: Float,
    color: Color,
) {
    if (x < 0f || x > size.width) return
    drawLine(
        color = color.copy(alpha = GHOST_ALPHA),
        start = Offset(x, GHOST_INSET.toPx()),
        end = Offset(x, size.height - GHOST_INSET.toPx()),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx())),
    )
}

/** A live chapter boundary. Skipped rather than clamped when off-window — see [TimelineGeometry]. */
private fun DrawScope.drawMarker(
    x: Float,
    color: Color,
    widthPx: Float,
    alpha: Float,
) {
    if (x < 0f || x > size.width) return
    drawLine(
        color = color.copy(alpha = alpha),
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = widthPx,
    )
}

/** The transport position, with a downward arrow so it is findable among the boundaries. */
private fun DrawScope.drawPlayhead(
    x: Float,
    color: Color,
) {
    if (x < 0f || x > size.width) return
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = PLAYHEAD_WIDTH.toPx(),
    )
    val arrow = PLAYHEAD_ARROW.toPx()
    drawPath(
        path =
            Path().apply {
                moveTo(x - arrow * ARROW_HALF_WIDTH, 0f)
                lineTo(x + arrow * ARROW_HALF_WIDTH, 0f)
                lineTo(x, arrow)
                close()
            },
        color = color,
    )
}
