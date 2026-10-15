package com.calypsan.listenup.client.core.image

import com.calypsan.listenup.api.dto.image.ImageFrame
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val ZOOM_SPAN = ln(ImageFrame.MAX_ZOOM / ImageFrame.MIN_ZOOM)
private const val TENTHS = 10

/**
 * The zoom a slider position in `0..1` stands for. The scale is logarithmic, so every equal stretch of the slider
 * multiplies the zoom by the same amount: the first half goes from 1x to 2x and the second from 2x to 4x. A linear
 * slider spent two thirds of its travel above 2x, where a face already fills the circle. Out-of-range or NaN
 * positions clamp.
 */
fun zoomForSliderPosition(position: Double): Double {
    val p = if (position.isNaN()) 0.0 else position.coerceIn(0.0, 1.0)
    return ImageFrame.MIN_ZOOM * (ImageFrame.MAX_ZOOM / ImageFrame.MIN_ZOOM).pow(p)
}

/** The slider position, in `0..1`, that shows [zoom]. The inverse of [zoomForSliderPosition]. */
fun sliderPositionForZoom(zoom: Double): Double {
    val z = if (zoom.isNaN()) ImageFrame.MIN_ZOOM else zoom.coerceIn(ImageFrame.MIN_ZOOM, ImageFrame.MAX_ZOOM)
    return ln(z / ImageFrame.MIN_ZOOM) / ZOOM_SPAN
}

/**
 * The zoom as people read it: one decimal, always, then "×". Built by hand because a JS double prints 1.0 as "1",
 * and Kotlin/Native has no `String.format`.
 */
fun zoomLabel(zoom: Double): String {
    val tenths = (zoom * TENTHS).roundToInt()
    return "${tenths / TENTHS}.${tenths % TENTHS}×"
}
