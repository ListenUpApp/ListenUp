package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The synopsis clamps by measured lines, and offers Read more only when the clamp hides something (spec D29). Native
 * graphics, so text wraps at real glyph widths and the measurement means something.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutClampTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val long = List(40) { "A sentence that keeps the synopsis going." }.joinToString(" ")

    private fun set(
        description: String,
        collapsedLines: Int?,
    ) {
        composeRule.setContent {
            MaterialTheme {
                AboutSection(
                    description = description,
                    genres = emptyList(),
                    tags = emptyList(),
                    moods = emptyList(),
                    isLoadingTags = false,
                    isCard = true,
                    collapsedLines = collapsedLines,
                    isDescriptionExpanded = false,
                    onToggleDescriptionExpanded = {},
                    onGenreClick = null,
                    onTagClick = {},
                    onMoodClick = {},
                )
            }
        }
        // The Markdown renderer parses off the main thread: wait until the text itself is drawn.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(description.take(12), substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a long description collapses and offers Read more as a button`() {
        set(long, collapsedLines = 2)
        composeRule.onNodeWithText("Read more").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun `a short description that fits shows no Read more`() {
        set("Short.", collapsedLines = 2)
        composeRule.onNodeWithText("Read more").assertDoesNotExist()
    }

    @Test
    fun `no clamp shows the whole text and no toggle (wide layouts)`() {
        set(long, collapsedLines = null)
        composeRule.onNodeWithText("Read more").assertDoesNotExist()
    }
}
