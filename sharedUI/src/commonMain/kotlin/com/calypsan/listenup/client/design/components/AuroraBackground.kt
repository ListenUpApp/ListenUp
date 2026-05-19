package com.calypsan.listenup.client.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ambient mesh gradient wash for the Now Playing screen.
 *
 * Renders 3-4 very large, overlapping radial gradients that slowly drift,
 * creating a smooth living color field with no visible edges or shapes.
 * Inspired by Apple Music's animated backgrounds.
 */
@Composable
fun AuroraBackground(
    dominantColor: Color,
    modifier: Modifier = Modifier,
) {
    if (dominantColor == Color.Transparent) return

    val transition = rememberInfiniteTransition(label = "aurora")

    // Very slow drift animations — 20-40s cycles, each layer different
    val phase0 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 28_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "mesh0",
    )
    val phase1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 36_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "mesh1",
    )
    val phase2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 22_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "mesh2",
    )
    val phase3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 40_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "mesh3",
    )

    // Derive hue-shifted wash colors — very low alpha
    val colors =
        remember(dominantColor) {
            listOf(
                dominantColor.copy(alpha = 0.18f), // dominant
                shiftHue(dominantColor, -30f).copy(alpha = 0.14f), // warmer
                shiftHue(dominantColor, 25f).copy(alpha = 0.16f), // cooler
                shiftHue(dominantColor, 150f).copy(alpha = 0.10f), // complementary hint
            )
        }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.95f },
    ) {
        val w = size.width
        val h = size.height
        val maxDim = maxOf(w, h)
        // Radius much larger than screen — edges always off-screen
        val gradientRadius = maxDim * 1.8f

        // Layer configs: anchor offset from center (fraction of screen),
        // drift ellipse radii (fraction of screen), phase offset
        data class Layer(
            val anchorX: Float,
            val anchorY: Float,
            val driftRx: Float,
            val driftRy: Float,
            val phase: Float,
            val phaseOffset: Float,
        )

        val layers =
            listOf(
                Layer(0.3f, 0.25f, 0.15f, 0.10f, phase0, 0f),
                Layer(0.7f, 0.65f, 0.12f, 0.18f, phase1, 45f),
                Layer(0.5f, 0.4f, 0.18f, 0.08f, phase2, 120f),
                Layer(0.35f, 0.75f, 0.10f, 0.14f, phase3, 200f),
            )

        layers.forEachIndexed { i, layer ->
            val rad = ((layer.phase + layer.phaseOffset) * PI / 180.0).toFloat()
            val cx = w * layer.anchorX + w * layer.driftRx * cos(rad)
            val cy = h * layer.anchorY + h * layer.driftRy * sin(rad * 0.7f)

            val center = Offset(cx, cy)
            val brush =
                Brush.radialGradient(
                    colors = listOf(colors[i], Color.Transparent),
                    center = center,
                    radius = gradientRadius,
                )

            drawRect(
                brush = brush,
                topLeft = Offset.Zero,
                size = Size(w, h),
            )
        }
    }
}

/**
 * Shift the hue of a color by [degrees].
 */
private fun shiftHue(
    color: Color,
    degrees: Float,
): Color {
    val r = color.red
    val g = color.green
    val b = color.blue

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f

    if (max == min) return color // achromatic

    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    var h =
        when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f

    h = (h + degrees).mod(360f)

    return hslToColor(h, s, l, color.alpha)
}

private fun hslToColor(
    h: Float,
    s: Float,
    l: Float,
    alpha: Float,
): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f).mod(2f) - 1f))
    val m = l - c / 2f

    val (r1, g1, b1) =
        when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha,
    )
}
