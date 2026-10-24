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
