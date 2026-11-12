package com.calypsan.listenup.client.design.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A catalogue description that closes its bold after a space still reads as bold, with no asterisks showing. */
@RunWith(RobolectricTestRunner::class)
class MarkdownTextEmphasisTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `bold closed after a space renders bold, not as asterisks`() {
        composeRule.setContent { ListenUpTheme(dynamicColor = false) { MarkdownText(markdown = DESCRIPTION) } }
        // The renderer parses off the first frame; wait for the paragraph to appear.
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(BOLD, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("**", substring = true).fetchSemanticsNodes().shouldBeEmpty()
        val text =
            composeRule
                .onNodeWithText(BOLD, substring = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .first()
        val start = text.text.indexOf(BOLD)
        text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start <= start && it.end >= start + BOLD.length } shouldBe
            true
    }

    private companion object {
        const val BOLD = "From a new voice, the truth."
        const val DESCRIPTION = "**$BOLD **\n\nLet us fly."
        const val TIMEOUT_MS = 5_000L
    }
}
