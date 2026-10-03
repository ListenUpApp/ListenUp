package com.calypsan.listenup.client.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/** Android's largest font-size step: the text size every large-text test renders at. */
const val LARGEST_FONT_SCALE = 2f

/** Renders [content] at [fontScale] over the test's own density — the "Largest" text setting. */
@Composable
fun AtFontScale(
    fontScale: Float = LARGEST_FONT_SCALE,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
}

/** The laid-out text of this text node (unmerged), as the platform measured it. */
fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode()
        .config
        .getOrNull(SemanticsActions.GetTextLayoutResult)
        ?.action
        ?.invoke(results)
    return results.single()
}

/**
 * Asserts this text node never breaks a word across lines — "Hardcove / r" or a column of single letters.
 * Needs `@GraphicsMode(NATIVE)`: legacy Robolectric fakes glyph widths, so every line would be one line.
 */
fun SemanticsNodeInteraction.assertNoMidWordBreaks(): SemanticsNodeInteraction {
    val layout = textLayout()
    val text = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        val before = text.getOrNull(end - 1)
        val after = text.getOrNull(end)
        withClue("'$text' breaks mid-word after '${text.substring(0, end)}'") {
            (before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true) shouldBe false
        }
    }
    return this
}

/**
 * Asserts this text node is drawn whole: no line clipped by a box too short for it, and none dropped to an
 * ellipsis. Height only — a single line in a wrap-content box reports a width "overflow" that is no overflow.
 */
fun SemanticsNodeInteraction.assertNotClipped(): SemanticsNodeInteraction {
    val text = textLayout()
    withClue("'${text.layoutInput.text.text}' is clipped or ellipsized") { text.didOverflowHeight shouldBe false }
    return this
}

/** Matches a node that TalkBack treats as a live region. */
fun isLiveRegion(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
