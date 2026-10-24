package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow as ChapterRowState
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.5.4 (PC1–PC4) on Android: opens centred on the current chapter, which says it is playing; a tap plays. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class ChapterPickerSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val selected = mutableListOf<Int>()

    private fun sheet(
        current: Int,
        chapterRemainingMs: Long = 480_000,
    ) {
        composeRule.setContent {
            MaterialTheme {
                ChapterPickerSheet(
                    chapters = chapters(current),
                    playingLeft = { shortDuration(chapterRemainingMs) },
                    onChapterSelected = { selected += it },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `it opens with the current chapter in view and centred`() {
        sheet(current = 38)
        val row = composeRule.onNodeWithTag(chapterRowTag(38)).fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag(CHAPTER_LIST_TAG).fetchSemanticsNode().boundsInRoot
        (abs(row.center.y - list.center.y) < row.height) shouldBe true
    }

    @Test
    fun `the current row says it is playing and how long is left`() {
        sheet(current = 5, chapterRemainingMs = 480_000)
        composeRule.onNodeWithText("Playing · 8m left").assertExists()
        composeRule
            .onNodeWithTag(chapterRowTag(5))
            .assertIsSelected()
            .assert(hasContentDescription("Chapter 6, Bridge Four, now playing, 8m left"))
    }

    @Test
    fun `other rows say what a tap does`() {
        sheet(current = 5)
        composeRule
            .onNodeWithTag(chapterRowTag(6))
            .assert(hasContentDescription("Play from chapter 7: Anything Reasonable, 33:18"))
    }

    @Test
    fun `rows show the chapter's duration, and the header sums the book`() {
        sheet(current = 5)
        composeRule.onNodeWithText("75 chapters · 45h 30m").assertExists()
        composeRule.onNodeWithText("33:18").assertExists()
    }

    @Test
    fun `a tap reports the chapter's index`() {
        sheet(current = 5)
        composeRule.onNodeWithTag(chapterRowTag(6)).performClick()
        selected shouldBe listOf(6)
    }

    @Test
    fun `each row keeps a 48dp target`() {
        sheet(current = 5)
        composeRule.onNodeWithTag(CHAPTER_LIST_TAG).performScrollToNode(hasTestTag(chapterRowTag(6)))
        composeRule.onNodeWithTag(chapterRowTag(6)).assertTouchTargetAtLeast()
    }

    private companion object {
        const val BOOK_MS = 163_800_000L // 45h 30m

        /** 75 chapters: 6 is "Bridge Four" (20:35), 7 is "Anything Reasonable" (33:18), and they sum to the book. */
        fun chapters(current: Int): List<ChapterRowState> {
            val named = mapOf(5 to ("Bridge Four" to 1_235_000L), 6 to ("Anything Reasonable" to 1_998_000L))
            val rest = (BOOK_MS - named.values.sumOf { it.second }) / 73
            val durations = (0 until 75).map { named[it]?.second ?: rest }.toMutableList()
            durations[74] += BOOK_MS - durations.sum()
            var start = 0L
            return (0 until 75).map { index ->
                ChapterRowState(
                    index = index,
                    number = index + 1,
                    title = named[index]?.first ?: "Chapter ${index + 1}",
                    startMs = start,
                    durationMs = durations[index],
                    isCurrent = index == current,
                ).also { start += durations[index] }
            }
        }
    }
}
