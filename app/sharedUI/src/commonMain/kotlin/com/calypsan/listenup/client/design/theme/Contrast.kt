package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
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
    return (lighter + FLARE) / (darker + FLARE)
}

/** WCAG's ambient-flare term, added to both luminances so pure black never divides by zero. */
private const val FLARE = 0.05

/** WCAG AA minimum for normal-size text. */
internal const val AA_TEXT = 4.5

/** WCAG AA minimum for large text and for non-text marks (icons, status dots, focus rings). */
internal const val AA_NON_TEXT = 3.0

/**
 * The scheme's coral set as text: [ColorScheme.primary] nudged toward [ColorScheme.onPrimaryContainer], which always
 * sits away from the surface (tone 10 in light, tone 90 in dark), so it clears AA on `surface` where the hand-tuned
 * light coral reads 4.48:1. For coral words — Now Playing's counter and "Playing on …"; fills keep [ColorScheme.primary].
 */
val ColorScheme.primaryInk: Color
    get() = lerp(primary, onPrimaryContainer, PRIMARY_INK_SHIFT)

private const val PRIMARY_INK_SHIFT = 0.2f
