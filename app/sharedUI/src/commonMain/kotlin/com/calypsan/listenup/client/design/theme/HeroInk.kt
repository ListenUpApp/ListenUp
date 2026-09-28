package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The one ink recipe for everything drawn on a color-block container: a hero band, and the selected
 * rows and cards that share its fill. The container's own `on…Container` ink is the loud voice; this
 * object supplies the three quieter ones, each tuned so it still clears WCAG AA on the container it
 * sits on.
 *
 * Each tier starts from a preferred alpha and moves only as far as contrast demands, so the recipe
 * holds on the hand-tuned light and dark schemes and on whatever a dynamic scheme produces:
 * - [muted]: secondary text and icons (overlines, metadata, supporting lines, placeholders) —
 *   raised toward full ink until it reaches 4.5:1 against the container.
 * - [wash]: the translucent fill of a chip, pill, icon button or inset card on the container —
 *   lowered until full ink still reads at 4.5:1 on top of it.
 * - [outline]: a field border or divider on the container — raised until it reaches 3:1.
 *
 * The no-argument overloads read the primary-container hero, the common case.
 */
object HeroInk {
    /** Preferred alpha for [muted] text before the contrast floor is applied. */
    const val MUTED_ALPHA = 0.8f

    /** Preferred alpha for a [wash] fill before the contrast floor is applied. */
    const val WASH_ALPHA = 0.1f

    /** Preferred alpha for an [outline] before the contrast floor is applied. */
    const val OUTLINE_ALPHA = 0.5f

    private const val ALPHA_STEP = 0.02f

    /** Secondary text or icon ink on [container], at least AA for text. */
    fun muted(
        ink: Color,
        container: Color,
    ): Color = ink.raisedUntil(container, from = MUTED_ALPHA, minimumContrast = AA_TEXT)

    /** A translucent fill of [ink] on [container] that keeps full [ink] on top of it AA for text. */
    fun wash(
        ink: Color,
        container: Color,
    ): Color {
        var alpha = WASH_ALPHA
        while (alpha > 0f && contrastRatio(ink, ink.copy(alpha = alpha).compositeOver(container)) < AA_TEXT) {
            alpha = (alpha - ALPHA_STEP).coerceAtLeast(0f)
        }
        return ink.copy(alpha = alpha)
    }

    /** A border or divider of [ink] on [container], at least AA for non-text marks. */
    fun outline(
        ink: Color,
        container: Color,
    ): Color = ink.raisedUntil(container, from = OUTLINE_ALPHA, minimumContrast = AA_NON_TEXT)

    /** [muted] ink on the primary-container hero. */
    @Composable
    fun muted(): Color = muted(MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)

    /** [wash] fill on the primary-container hero. */
    @Composable
    fun wash(): Color = wash(MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)

    /** [muted] ink for text inside a [wash] card or chip on the primary-container hero. */
    @Composable
    fun mutedOnWash(): Color {
        val ink = MaterialTheme.colorScheme.onPrimaryContainer
        val container = MaterialTheme.colorScheme.primaryContainer
        return muted(ink, wash(ink, container).compositeOver(container))
    }

    /** [outline] on the primary-container hero. */
    @Composable
    fun outline(): Color =
        outline(MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)

    private fun Color.raisedUntil(
        container: Color,
        from: Float,
        minimumContrast: Double,
    ): Color {
        var alpha = from
        while (alpha < 1f && contrastRatio(copy(alpha = alpha), container) < minimumContrast) {
            alpha = (alpha + ALPHA_STEP).coerceAtMost(1f)
        }
        return copy(alpha = alpha)
    }
}
