package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.components.CHAPTER_PANEL_TAG
import com.calypsan.listenup.client.features.nowplaying.components.CHAPTER_ROW_BAR_TAG
import com.calypsan.listenup.client.features.nowplaying.components.NowPlayingChapterPanel
import com.calypsan.listenup.client.features.nowplaying.components.PlayerChapterRow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tablet's chapter panel (spec §3.11, §5.3, `NP-AndroidTablet`): it opens centred on the current chapter, follows a
 * chapter change only while the old current row was in view, and otherwise offers "Back to current chapter". The
 * current row's chapter bar inside it is linear, 80 × 4dp, drawn only on that row, and silent to TalkBack, whose row
 * label already says how long is left (§8).
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

    private val seeks = mutableListOf<Int>()
    private lateinit var listState: LazyListState

    private fun panel(current: MutableIntState) {
        composeRule.setContent {
            listState = rememberLazyListState()
            MaterialTheme {
                Box(Modifier.size(width = 340.dp, height = 720.dp)) {
                    NowPlayingChapterPanel(
                        rows = ROWS,
                        currentIndex = current.intValue,
                        bookDurationMs = FIXTURE_BOOK_MS,
                        progress = { samplePlayerProgress },
                        onSeekToChapter = { seeks += it },
                        listState = listState,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun panel(current: Int) = panel(mutableIntStateOf(current))

    /** How far row [index]'s centre sits from the list's centre, in px. */
    private fun centreOffset(index: Int): Float {
        val row = composeRule.onNodeWithTag(chapterRowTag(index)).fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).fetchSemanticsNode().boundsInRoot
        return abs(row.center.y - list.center.y)
    }

    private val rowHeight: Float get() = with(composeRule.density) { 56.dp.toPx() }

    private fun firstVisibleIndex(): Int = composeRule.runOnIdle { listState.firstVisibleItemIndex }

    @Test
    fun `it opens with the current chapter centred`() {
        panel(current = 38)
        centreOffset(38) shouldBeLessThan rowHeight
        composeRule.onNodeWithText("Back to current chapter").assertDoesNotExist()
    }

    @Test
    fun `a chapter change follows while the old current row was in view`() {
        val current = mutableIntStateOf(10)
        panel(current)
        current.intValue = 20
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(chapterRowTag(20)).assertIsDisplayed()
        centreOffset(20) shouldBeLessThan rowHeight
        composeRule.onNodeWithText("Back to current chapter").assertDoesNotExist()
    }

    @Test
    fun `scrolled away, a chapter change holds the list and offers Back to current chapter`() {
        val current = mutableIntStateOf(10)
        panel(current)
        composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).performScrollToIndex(70)
        composeRule.waitForIdle()
        val before = firstVisibleIndex()
        current.intValue = 11
        composeRule.waitForIdle()
        firstVisibleIndex() shouldBe before
        composeRule.onNodeWithText("Back to current chapter").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        centreOffset(11) shouldBeLessThan rowHeight
        composeRule.onNodeWithText("Back to current chapter").assertDoesNotExist()
    }

    @Test
    fun `a manual scroll away shows Back to current chapter even without a chapter change`() {
        panel(current = 10)
        composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).performScrollToIndex(70)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Back to current chapter").assertIsDisplayed()
    }

    @Test
    fun `a tap plays from that chapter`() {
        panel(current = 5)
        composeRule.onNodeWithTag(chapterRowTag(6)).performClick()
        seeks shouldBe listOf(6)
    }

    @Test
    fun `rows say what a tap does, and the current one says it is playing`() {
        panel(current = 5)
        composeRule
            .onNodeWithTag(chapterRowTag(6))
            .assert(hasContentDescription("Play from chapter 7: Anything Reasonable, 33:18"))
        composeRule
            .onNodeWithTag(chapterRowTag(5))
            .assert(hasContentDescription("Chapter 6, Bridge Four, now playing, 8m left"))
    }

    @Test
    fun `the header names the panel and sums the book`() {
        panel(current = 5)
        composeRule.onNodeWithText("Chapters").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("75 chapters · 45h 30m").assertExists()
    }

    @Test
    fun `only the current row carries the bar`() {
        panel(current = 5)
        composeRule.onAllNodesWithTag(CHAPTER_ROW_BAR_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    private companion object {
        val ROWS = bookChapterRows(current = -1)
    }
}
