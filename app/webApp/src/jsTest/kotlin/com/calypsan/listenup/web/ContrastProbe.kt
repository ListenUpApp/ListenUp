package com.calypsan.listenup.web

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A computed `rgb(…)`/`rgba(…)` colour as its three channels, 0–255. */
internal fun rgb(computed: String): List<Double> =
    Regex("""rgba?\(([^)]*)\)""")
        .find(computed)!!
        .groupValues[1]
        .split(",")
        .take(3)
        .map { it.trim().toDouble() }

/** WCAG relative luminance of [rgb]. */
internal fun luminance(rgb: List<Double>): Double {
    fun channel(value: Double): Double {
        val c = value / 255
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2])
}

/** WCAG contrast ratio between two colours. */
internal fun contrast(
    a: List<Double>,
    b: List<Double>,
): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/**
 * A computed colour as red, green, blue (0–255) and alpha (0–1). Reads both forms Chromium computes: `rgb(…)`/`rgba(…)`
 * and the `color(srgb r g b / a)` a `color-mix()` resolves to, whose channels are 0–1.
 */
internal fun rgba(computed: String): List<Double> {
    Regex("""color\(srgb ([^)]*)\)""").find(computed)?.let { match ->
        val (channels, alpha) = match.groupValues[1].split("/").let { it[0] to it.getOrNull(1) }
        val rgb =
            channels
                .trim()
                .split(Regex("\\s+"))
                .take(3)
                .map { it.toDouble() * 255 }
        return rgb + (alpha?.trim()?.toDouble() ?: 1.0)
    }
    val parts =
        Regex("""rgba?\(([^)]*)\)""")
            .find(computed)!!
            .groupValues[1]
            .split(",")
            .map { it.trim().toDouble() }
    return parts.take(3) + (parts.getOrNull(3) ?: 1.0)
}

/** [top] (from [rgba]) painted over the opaque [ground], as the eye sees it. */
internal fun over(
    top: List<Double>,
    ground: List<Double>,
): List<Double> {
    val alpha = top[3]
    return (0..2).map { top[it] * alpha + ground[it] * (1 - alpha) }
}
