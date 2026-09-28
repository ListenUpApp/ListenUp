package com.calypsan.listenup.client.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * WCAG 2.x contrast ratio between [foreground] and [background], from 1.0 (identical) to
 * 21.0 (black on white). A translucent [foreground] is first composited over [background],
 * which is what the eye actually sees.
 */
internal fun contrastRatio(
    foreground: Color,
    background: Color,
): Double {
    val seen = if (foreground.alpha < 1f) foreground.compositeOver(background) else foreground
    val lighter = maxOf(seen.luminance(), background.luminance()).toDouble()
    val darker = minOf(seen.luminance(), background.luminance()).toDouble()
    return (lighter + 0.05) / (darker + 0.05)
}

/** WCAG AA minimum for normal-size text. */
internal const val AA_TEXT = 4.5

/** WCAG AA minimum for large text and for non-text marks (icons, status dots, focus rings). */
internal const val AA_NON_TEXT = 3.0
