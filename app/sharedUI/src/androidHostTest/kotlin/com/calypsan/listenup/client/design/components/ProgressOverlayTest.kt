package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The progress pill sits on every in-progress cover, so it has to survive the largest font
 * scale Android offers: at 2.0× neither its percentage nor its time-remaining text may be
 * clipped by a fixed-height box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ProgressOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `at font scale 2 the pill grows to hold its text`() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(240.dp)) {
                        ProgressOverlay(
                            progress = 0.42f,
                            timeRemaining = "5m",
                            modifier = Modifier.testTag(PILL),
                        )
                    }
                }
            }
        }

        val pill = composeRule.onNodeWithTag(PILL).boundsInRoot()
        listOf("42%", "5m").forEach { text ->
            val node = composeRule.onNodeWithText(text)
            // Height only: Robolectric's legacy text shaping reports near-zero glyph widths, so
            // width overflow is an artefact here. Vertical clipping is the bug being pinned.
            withClue("\"$text\" is not clipped vertically") {
                node.textLayout().didOverflowHeight shouldBe false
            }
            withClue("\"$text\" sits inside the pill") {
                pill.contains(node.boundsInRoot()) shouldBe true
            }
        }
    }

    private fun SemanticsNodeInteraction.boundsInRoot(): Rect = fetchSemanticsNode().boundsInRoot

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.single()
    }

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    private companion object {
        const val PILL = "progress-pill"
    }
}
