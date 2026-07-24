package com.calypsan.listenup.client.features.genredestination

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Computes the CSS-`linear-gradient(<angle>, …)`-equivalent start/end points for [colors] over a
 * box of [size]: the gradient line runs through the box center at [angleDegrees] (0° = "to top",
 * clockwise), extended to the corners so the full box is covered — matching the CSS spec algorithm.
 * Falls back to a plain vertical gradient before the first layout pass, when [size] is still empty.
 */
internal fun angledGradientBrush(
    colors: List<Color>,
    angleDegrees: Float,
    size: Size,
): Brush {
    if (size.width <= 0f || size.height <= 0f) return Brush.verticalGradient(colors)

    val angleRad = angleDegrees * (PI / 180.0)
    val dx = sin(angleRad).toFloat()
    val dy = -cos(angleRad).toFloat()

    val cx = size.width / 2f
    val cy = size.height / 2f
    val corners =
        listOf(
            Offset(0f, 0f),
            Offset(size.width, 0f),
            Offset(0f, size.height),
            Offset(size.width, size.height),
        )
    val projections = corners.map { (it.x - cx) * dx + (it.y - cy) * dy }
    val minProjection = projections.min()
    val maxProjection = projections.max()

    return Brush.linearGradient(
        colors = colors,
        start = Offset(cx + dx * minProjection, cy + dy * minProjection),
        end = Offset(cx + dx * maxProjection, cy + dy * maxProjection),
    )
}
