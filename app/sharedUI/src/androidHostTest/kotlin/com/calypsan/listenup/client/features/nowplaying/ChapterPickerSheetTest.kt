package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import androidx.compose.ui.text.style.TextDirection
import com.calypsan.listenup.client.design.text.textDirection
import com.calypsan.listenup.client.design.text.ThemedFor
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Rule
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
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
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            ThemedFor(rtl) {
                ChapterPickerSheet(
                    chapters = bookChapterRows(current),
                    playingLeft = { chapterRemainingMs.milliseconds.inWholeMinutes.minutes },
                    onChapterSelected = { selected += it },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `in a right-to-left layout a chapter's length reads left to right`() {
        sheet(current = 5, rtl = true)
        val clock = Regex("""\d+:\d{2}(:\d{2})?""")
        val length =
            composeRule.onAllNodes(
                SemanticsMatcher("a clock") { node ->
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .any { clock.matches(it.text) }
                },
                useUnmergedTree = true,
            )[0]
        length.textDirection() shouldBe TextDirection.ContentOrLtr
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
            .assert(hasContentDescription("Chapter 6, Bridge Four, now playing, 8 minutes left"))
    }

    @Test
    fun `TalkBack hears the time left in words, as long as it is`() {
        sheet(current = 5, chapterRemainingMs = 3_930_000)
        composeRule.onNodeWithText("Playing · 1h 5m left").assertExists()
        composeRule
            .onNodeWithTag(chapterRowTag(5))
            .assert(hasContentDescription("Chapter 6, Bridge Four, now playing, 1 hour 5 minutes left"))
    }

    @Test
    fun `TalkBack hears the last minute as less than a minute, never as none`() {
        sheet(current = 5, chapterRemainingMs = 42_000)
        composeRule
            .onNodeWithTag(chapterRowTag(5))
            .assert(hasContentDescription("Chapter 6, Bridge Four, now playing, less than a minute left"))
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
}
