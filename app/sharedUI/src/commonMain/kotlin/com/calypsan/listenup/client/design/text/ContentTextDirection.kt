package com.calypsan.listenup.client.design.text

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * The direction [text] reads in, from its first letter that has one (Unicode's "first strong" rule), or null when it
 * has none: digits, punctuation and spaces alone ("-1:52") take their direction from what surrounds them. Pure.
 */
fun firstStrongDirection(text: String): LayoutDirection? =
    text.firstOrNull { it.isLetter() }?.let { letter ->
        if (letter.isRightToLeftScript()) LayoutDirection.Rtl else LayoutDirection.Ltr
    }

/**
 * The code points of the right-to-left scripts: Hebrew, Arabic, Syriac, Thaana, N'Ko, Samaritan and Mandaic, then the
 * Hebrew and Arabic presentation forms.
 */
private val RightToLeftScripts = listOf(0x0590..0x08FF, 0xFB1D..0xFDFF, 0xFE70..0xFEFF)

private fun Char.isRightToLeftScript(): Boolean = RightToLeftScripts.any { code in it }

/**
 * This typography with every style reading its direction from its own text, and left to right when the text has no
 * letters to read it from ([TextDirection.ContentOrLtr]), aligned to the [layoutDirection]'s start edge.
 *
 * Compose's default lays every paragraph out in the layout's direction, so in an Arabic or Hebrew locale, where the app
 * shows its English strings, "Good afternoon," became ",Good afternoon" and "1-day streak" became "day streak-1".
 * Reading the direction from the text fixes that for every string at once. A label of digits and symbols alone (a
 * clock "-1:52", a speed "1.0×", a countdown) has no letter to decide by; Android's firstStrong would fall back to the
 * layout and print "1.0×" as "×1.0", so it reads left to right, as numbers do in those locales too. Aligning to the
 * layout's start edge by side (left or right) rather than by the paragraph's own start keeps text where the mirrored
 * layout puts it.
 */
fun Typography.readingFromContent(layoutDirection: LayoutDirection): Typography {
    val align = if (layoutDirection == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left

    fun TextStyle.reading(): TextStyle = copy(textDirection = TextDirection.ContentOrLtr, textAlign = align)

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    return copy(
        displayLarge = displayLarge.reading(),
        displayMedium = displayMedium.reading(),
        displaySmall = displaySmall.reading(),
        headlineLarge = headlineLarge.reading(),
        headlineMedium = headlineMedium.reading(),
        headlineSmall = headlineSmall.reading(),
        titleLarge = titleLarge.reading(),
        titleMedium = titleMedium.reading(),
        titleSmall = titleSmall.reading(),
        bodyLarge = bodyLarge.reading(),
        bodyMedium = bodyMedium.reading(),
        bodySmall = bodySmall.reading(),
        labelLarge = labelLarge.reading(),
        labelMedium = labelMedium.reading(),
        labelSmall = labelSmall.reading(),
        displayLargeEmphasized = displayLargeEmphasized.reading(),
        displayMediumEmphasized = displayMediumEmphasized.reading(),
        displaySmallEmphasized = displaySmallEmphasized.reading(),
        headlineLargeEmphasized = headlineLargeEmphasized.reading(),
        headlineMediumEmphasized = headlineMediumEmphasized.reading(),
        headlineSmallEmphasized = headlineSmallEmphasized.reading(),
        titleLargeEmphasized = titleLargeEmphasized.reading(),
        titleMediumEmphasized = titleMediumEmphasized.reading(),
        titleSmallEmphasized = titleSmallEmphasized.reading(),
        bodyLargeEmphasized = bodyLargeEmphasized.reading(),
        bodyMediumEmphasized = bodyMediumEmphasized.reading(),
        bodySmallEmphasized = bodySmallEmphasized.reading(),
        labelLargeEmphasized = labelLargeEmphasized.reading(),
        labelMediumEmphasized = labelMediumEmphasized.reading(),
        labelSmallEmphasized = labelSmallEmphasized.reading(),
    )
}

/**
 * Lays [content] out in [direction], with the theme's typography re-aligned to it, so a text style's side
 * ([readingFromContent]) and [TextAlign.Companion.LayoutStart] agree inside, as they do at the root. The only way this
 * app gives a subtree its own layout direction.
 *
 * The limit: a style read from the theme outside and handed in (a `style` parameter) still carries the outer side; a
 * component that takes one re-aligns it inside with `style.copy(textAlign = TextAlign.LayoutStart)`, as the byline does.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProvideLayoutDirection(
    direction: LayoutDirection,
    content: @Composable () -> Unit,
) {
    val typography = MaterialTheme.typography
    val aligned = remember(typography, direction) { typography.readingFromContent(direction) }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            motionScheme = MaterialTheme.motionScheme,
            shapes = MaterialTheme.shapes,
            typography = aligned,
            content = content,
        )
    }
}

/** Every style of this typography, the fifteen baseline roles then their emphasized twins. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun Typography.allStyles(): List<TextStyle> =
    listOf(
        displayLarge,
        displayMedium,
        displaySmall,
        headlineLarge,
        headlineMedium,
        headlineSmall,
        titleLarge,
        titleMedium,
        titleSmall,
        bodyLarge,
        bodyMedium,
        bodySmall,
        labelLarge,
        labelMedium,
        labelSmall,
        displayLargeEmphasized,
        displayMediumEmphasized,
        displaySmallEmphasized,
        headlineLargeEmphasized,
        headlineMediumEmphasized,
        headlineSmallEmphasized,
        titleLargeEmphasized,
        titleMediumEmphasized,
        titleSmallEmphasized,
        bodyLargeEmphasized,
        bodyMediumEmphasized,
        bodySmallEmphasized,
        labelLargeEmphasized,
        labelMediumEmphasized,
        labelSmallEmphasized,
    )

/**
 * The layout's start edge as a side: left, or right in a right-to-left layout. Use it instead of `Start`,
 * which follows the text's own direction once text reads from its content ([readingFromContent]), and would send
 * English text to the left of an Arabic layout.
 */
val TextAlign.Companion.LayoutStart: TextAlign
    @Composable @ReadOnlyComposable
    get() = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left

/** The layout's end edge as a side: right, or left in a right-to-left layout. Use it instead of `End`. */
val TextAlign.Companion.LayoutEnd: TextAlign
    @Composable @ReadOnlyComposable
    get() = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Left else TextAlign.Right
