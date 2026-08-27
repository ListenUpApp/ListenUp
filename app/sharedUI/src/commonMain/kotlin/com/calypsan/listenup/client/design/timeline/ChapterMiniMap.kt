package com.calypsan.listenup.client.design.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val MINIMAP_HEIGHT = 46.dp
private val TICK_GAP_DP = 1.dp
private const val TICK_MIN_HEIGHT_FRACTION = 0.28f
private const val TICK_HEIGHT_RANGE = 0.36f
private val VIEWPORT_MIN_WIDTH = 14.dp
private val VIEWPORT_BORDER = 2.dp
private const val VIEWPORT_FILL_ALPHA = 0.16f

/**
 * The whole book at a glance, with a window showing where the detail lane is looking.
 *
 * This is the only view that ever shows all 311 chapters at once, and it deliberately does not try
 * to show them *as* chapters: at this scale a marker per chapter is one mark every four pixels, a
 * grey smear carrying no information. It draws [chapterDensity] instead — shaded buckets, tall
 * where chapters cluster — so the book's shape is legible even when its individual boundaries
 * cannot be.
 *
 * It is a navigation instrument, never a precision one. Tapping or dragging moves the detail lane's
 * window; nothing here edits anything, because at roughly 195 seconds per pixel on a 65-hour book
 * there is no honest way to place a boundary from this surface.
 *
 * @param density per-bucket weights in `0f..1f` from [chapterDensity].
 * @param viewportStartFraction left edge of the detail lane's window, as a fraction of the book.
 * @param viewportEndFraction right edge of that window. Clamped so the marker stays visible even
 *   when the window is a vanishingly small slice of a long book.
 * @param onSeekFraction called with a `0f..1f` position when the user taps or drags to move the
 *   window. The caller decides what the window's new length is; this only says where.
 * @param modifier Modifier for the whole minimap.
 * @param height overall height; the mobile layout draws this shorter than the desktop one.
 * @param contentDescription spoken description, since the density shading is meaningless to a
 *   screen reader on its own.
 */
@Composable
fun ChapterMiniMap(
    density: List<Float>,
    viewportStartFraction: Float,
    viewportEndFraction: Float,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = MINIMAP_HEIGHT,
    contentDescription: String? = null,
) {
    val tickColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val viewportColor = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (size.width > 0) onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }.pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        if (size.width > 0) onSeekFraction((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }.then(
                    if (contentDescription == null) {
                        Modifier
                    } else {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    },
                ),
    ) {
        if (density.isEmpty() || size.width <= 0f) return@Canvas

        val gap = TICK_GAP_DP.toPx()
        val slot = size.width / density.size
        val tickWidth = (slot - gap).coerceAtLeast(1f)

        density.forEachIndexed { i, weight ->
            val h = size.height * (TICK_MIN_HEIGHT_FRACTION + TICK_HEIGHT_RANGE * weight)
            drawRoundRect(
                color = tickColor,
                topLeft = Offset(x = i * slot, y = (size.height - h) / 2f),
                size = Size(width = tickWidth, height = h),
                cornerRadius = CornerRadius(1f, 1f),
            )
        }

        // The window the detail lane is showing. On a 65-hour book a 30-minute window is 0.7% of
        // the width — under a pixel — so it is floored to a visible size rather than drawn
        // truthfully and disappearing exactly when the user has zoomed in far enough to need it.
        val left = viewportStartFraction.coerceIn(0f, 1f) * size.width
        val rawWidth = (viewportEndFraction - viewportStartFraction).coerceAtLeast(0f) * size.width
        val width = rawWidth.coerceAtLeast(VIEWPORT_MIN_WIDTH.toPx()).coerceAtMost(size.width)
        val x = left.coerceAtMost(size.width - width)
        val corner = CornerRadius(8f, 8f)

        drawRoundRect(
            color = viewportColor.copy(alpha = VIEWPORT_FILL_ALPHA),
            topLeft = Offset(x, 0f),
            size = Size(width, size.height),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = viewportColor,
            topLeft = Offset(x + VIEWPORT_BORDER.toPx() / 2f, VIEWPORT_BORDER.toPx() / 2f),
            size = Size(width - VIEWPORT_BORDER.toPx(), size.height - VIEWPORT_BORDER.toPx()),
            cornerRadius = corner,
            style = Stroke(width = VIEWPORT_BORDER.toPx()),
        )
    }
}
