package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.components.CHAPTER_ROW_BAR_TAG
import com.calypsan.listenup.client.features.nowplaying.components.PlayerChapterRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tablet's chapter panel (spec §3.11, §5.3, `NP-AndroidTablet`) and the current row's chapter bar inside it: linear,
 * 80 × 4dp, drawn only on the current row of the panel, and silent to TalkBack, whose row label already says how long
 * is left (§8).
 */
@Config(qualifiers = "w1280dp-h800dp-land")
@RunWith(RobolectricTestRunner::class)
class NowPlayingChapterPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun row(
        isCurrent: Boolean,
        chapterFraction: (() -> Float)?,
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlayerChapterRow(
                    number = 6,
                    title = "Bridge Four",
                    durationLabel = "20:35",
                    isCurrent = isCurrent,
                    playingLeft = { "8m" },
                    chapterFraction = chapterFraction,
                    onClick = {},
                )
            }
        }
    }

    @Test
    fun `the current row draws a bar when given progress`() {
        row(isCurrent = true, chapterFraction = { 0.59f })
        composeRule
            .onNodeWithTag(CHAPTER_ROW_BAR_TAG, useUnmergedTree = true)
            .assertWidthIsEqualTo(80.dp)
            .assertHeightIsEqualTo(4.dp)
    }

    @Test
    fun `a row that isn't current never draws one`() {
        row(isCurrent = false, chapterFraction = { 0.59f })
        composeRule.onNodeWithTag(CHAPTER_ROW_BAR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the sheet's current row, given no progress, draws none`() {
        row(isCurrent = true, chapterFraction = null)
        composeRule.onNodeWithTag(CHAPTER_ROW_BAR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the bar adds nothing to what TalkBack reads`() {
        row(isCurrent = true, chapterFraction = { 0.59f })
        composeRule
            .onNodeWithTag(CHAPTER_ROW_BAR_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        composeRule
            .onNode(hasContentDescription("Chapter 6, Bridge Four, now playing, 8m left"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
    }
}
