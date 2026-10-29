package com.calypsan.listenup.client.design.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
import com.calypsan.listenup.client.design.text.textAlign
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A description is prose, so it aligns to its own paragraphs, as Android's TextView does: an Arabic description in an
 * English layout runs flush right, not ragged against the left edge where a UI label would sit.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownTextDirectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a description's paragraphs align to their own start`() {
        composeRule.setContent { ListenUpTheme(dynamicColor = false) { MarkdownText(markdown = ARABIC) } }
        // The renderer parses off the first frame; wait for the paragraph to appear.
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(WORD, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(WORD, substring = true).textAlign() shouldBe TextAlign.Start
    }

    private companion object {
        const val ARABIC = "رواية عن الحرب والسلام."
        const val WORD = "رواية"
        const val TIMEOUT_MS = 5_000L
    }
}
