package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import com.calypsan.listenup.client.presentation.chaptereditor.DriftProposal
import com.calypsan.listenup.client.presentation.chaptereditor.DriftRefusal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The drift panel's refusals and its one destructive button.
 *
 * Applying drift rewrites every boundary in the book at once, so the thing worth pinning down is
 * that Apply cannot fire on a proposal the engine refused, and that a refusal explains itself
 * rather than leaving a greyed-out button to be interpreted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class DriftSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapters =
        List(3) { i ->
            Chapter(id = "c$i", title = "Chapter ${i + 1}", duration = 10_000L, startTime = i * 10_000L)
        }

    private fun render(
        drift: ChapterEditorUiState.DriftState,
        hasSelection: Boolean = true,
        hasPlayhead: Boolean = true,
    ) {
        composeRule.setContent {
            MaterialTheme {
                DriftSheet(
                    drift = drift,
                    chapters = chapters,
                    hasSelection = hasSelection,
                    hasPlayhead = hasPlayhead,
                    onPin = {},
                    onApply = {},
                    onCancel = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun proposalOf(second: ChapterAnchor? = null) = DriftProposal(first = ChapterAnchor("c0", 1_000L), second = second)

    @Test
    fun `an inverted proposal names the mistake instead of just disabling apply`() {
        render(
            ChapterEditorUiState.DriftState(
                proposal = proposalOf(ChapterAnchor("c2", 500L)),
                preview = DriftPreview.Refused(DriftRefusal.InvertedAnchors),
            ),
        )

        composeRule.onNodeWithText("Audio does not run backwards", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Apply correction").assertIsNotEnabled()
    }

    @Test
    fun `unusable anchors are refused and apply stays shut`() {
        render(
            ChapterEditorUiState.DriftState(
                proposal = proposalOf(),
                preview = DriftPreview.Refused(DriftRefusal.UnusableAnchors),
            ),
        )

        composeRule.onNodeWithText("Pin two different chapters", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Apply correction").assertIsNotEnabled()
    }

    @Test
    fun `a ready proposal quotes how many boundaries move and opens apply`() {
        render(
            ChapterEditorUiState.DriftState(
                proposal = proposalOf(ChapterAnchor("c2", 25_000L)),
                preview =
                    DriftPreview.Ready(
                        corrected = chapters,
                        affectedCount = 311,
                        firstOffsetMs = 1_000L,
                        lastOffsetMs = 6_000L,
                    ),
            ),
        )

        composeRule.onNodeWithText("311 boundaries move", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Apply correction").assertIsEnabled()
    }

    @Test
    fun `an open flow with nothing pinned asks for a chapter rather than reporting an error`() {
        render(ChapterEditorUiState.DriftState(), hasSelection = false)

        composeRule.onNodeWithText("Choose a chapter in the list", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Apply correction").assertIsNotEnabled()
    }

    @Test
    fun `with a chapter chosen but nothing playing it asks for the playhead specifically`() {
        render(ChapterEditorUiState.DriftState(), hasSelection = true, hasPlayhead = false)

        composeRule.onNodeWithText("Play the book to the spot", substring = true).assertIsDisplayed()
    }
}
