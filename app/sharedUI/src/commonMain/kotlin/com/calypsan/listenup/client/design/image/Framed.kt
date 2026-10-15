package com.calypsan.listenup.client.design.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import coil3.compose.ConstraintsSizeResolver
import coil3.compose.rememberConstraintsSizeResolver
import coil3.size.Dimension
import coil3.size.SizeResolver
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.frameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scales a photo the way its [frame] says: far enough to cover the box, times the frame's zoom.
 * A null frame is exactly [ContentScale.Crop], so every unframed photo looks as it did before framing
 * existed. Pair it with [FramedAlignment] on the same image.
 */
@Immutable
class FramedCrop(
    private val frame: ImageFrame?,
) : ContentScale {
    override fun computeScaleFactor(
        srcSize: Size,
        dstSize: Size,
    ): ScaleFactor {
        if (!srcSize.isSpecified || srcSize.minDimension <= 0f) return ScaleFactor(1f, 1f)
        val scale =
            frameLayout(
                imageWidth = srcSize.width.toDouble(),
                imageHeight = srcSize.height.toDouble(),
                viewportWidth = dstSize.width.toDouble(),
                viewportHeight = dstSize.height.toDouble(),
                frame = frame,
            ).scale.toFloat()
        return ScaleFactor(scale, scale)
    }

    override fun equals(other: Any?): Boolean = other is FramedCrop && other.frame == frame

    override fun hashCode(): Int = frame.hashCode()
}

/**
 * Places a scaled photo so the frame's focus point sits at the box's centre, as far as the box stays
 * covered. It receives the size *after* [FramedCrop] scaled it, and `frameLayout` is invariant to the
 * image's own scale, so passing that size straight back in gives the same offsets without knowing
 * the scale. It ignores [LayoutDirection] on purpose: a face is not text and must not mirror in RTL.
 */
@Immutable
class FramedAlignment(
    private val frame: ImageFrame?,
) : Alignment {
    override fun align(
        size: IntSize,
        space: IntSize,
        layoutDirection: LayoutDirection,
    ): IntOffset {
        val layout =
            frameLayout(
                imageWidth = size.width.toDouble(),
                imageHeight = size.height.toDouble(),
                viewportWidth = space.width.toDouble(),
                viewportHeight = space.height.toDouble(),
                frame = frame,
            )
        return IntOffset(layout.offsetX.roundToInt(), layout.offsetY.roundToInt())
    }

    override fun equals(other: Any?): Boolean = other is FramedAlignment && other.frame == frame

    override fun hashCode(): Int = frame.hashCode()
}

/** The longest edge Coil decodes a framed photo to. Pickers already cap uploads at 2,048 px. */
internal const val MAX_FRAMED_DECODE_PX = 2048

/** How many pixels to decode for a [base]-pixel box shown at [zoom]. */
internal fun zoomedPixels(
    base: Int,
    zoom: Double?,
): Int = min(MAX_FRAMED_DECODE_PX, (base * (zoom ?: 1.0)).roundToInt())

/**
 * A Coil size resolver that asks for the laid-out size times the frame's zoom. Without it, Coil
 * decodes to the box's size and a 2x frame draws a 2x-upscaled thumbnail. Apply the first of the
 * returned pair as a modifier on the image (`Modifier.then(constraints)`) and pass the second to
 * `ImageRequest.Builder.size`.
 */
@Composable
fun rememberZoomedSizeResolver(frame: ImageFrame?): Pair<ConstraintsSizeResolver, SizeResolver> {
    val constraints = rememberConstraintsSizeResolver()
    val zoom = frame?.zoom
    val zoomed =
        remember(constraints, zoom) {
            SizeResolver {
                val base = constraints.size()
                coil3.size.Size(base.width.zoomed(zoom), base.height.zoomed(zoom))
            }
        }
    return constraints to zoomed
}

private fun Dimension.zoomed(zoom: Double?): Dimension =
    when (this) {
        is Dimension.Pixels -> Dimension(zoomedPixels(px, zoom))
        Dimension.Undefined -> this
    }
