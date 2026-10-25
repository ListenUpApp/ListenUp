package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.features.nowplaying.components.CHAPTER_PANEL_TAG
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.testing.textLayout
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `NP-AndroidTablet` at 1280×800 (spec §5.3, P3, P10, P22, P33): cover · player column · chapter panel. The header
 * reads "Now playing"; the series is a link above the title, so More leaves Go to series out; Chapters leaves the
 * settings row, because the list is always there.
 */
@Config(qualifiers = "w1280dp-h800dp-land")
@RunWith(RobolectricTestRunner::class)
class WideNowPlayingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val picked = mutableListOf<PlayerMenuItem>()
    private val seeks = mutableListOf<Int>()
    private val panelShown = mutableListOf<Boolean>()
    private lateinit var titleMedium: TextStyle

    private fun wide(state: PlayerUiState.Loaded = chapteredState()) {
        composeRule.setContent {
            MaterialTheme {
                titleMedium = MaterialTheme.typography.titleMedium
                TestWideNowPlaying(
                    state = state,
                    onMenuItem = { picked += it },
                    onSeekToChapter = { seeks += it },
                    onChapterPanelShown = { panelShown += it },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a landscape tablet shows cover, player and panel, and no Chapters button`() {
        wide()
        composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Chapters").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        panelShown.last() shouldBe true
    }

    @Test
    fun `the header reads Now playing in titleMedium, with the status and More`() {
        wide()
        val style =
            composeRule
                .onNodeWithText("Now playing")
                .textLayout()
                .layoutInput.style
        style.fontSize shouldBe titleMedium.fontSize
        style.fontWeight shouldBe FontWeight.Medium
    }

    @Test
    fun `the header carries the status and More, and no series`() {
        wide()
        composeRule.onNodeWithText("Downloaded").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More options").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
    }

    @Test
    fun `the series is a link above the title, and More leaves Go to series out`() {
        wide(chapteredState(series = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1"))))
        composeRule.onNodeWithText("The Stormlight Archive · Book 1").assertHasClickAction().performClick()
        picked shouldBe listOf(PlayerMenuItem.GoToSeries("s1"))
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Go to book").assertExists()
        composeRule.onNodeWithText("Go to series").assertDoesNotExist()
    }

    @Test
    fun `a series with no place in it reads as its bare name`() {
        wide(chapteredState(series = listOf(PlayerSeriesRef("s1", "The Cosmere", null))))
        composeRule.onNodeWithText("The Cosmere").assertHasClickAction()
    }

    @Test
    fun `the series link sits above the title`() {
        wide()
        val series = composeRule.onNodeWithText("The Stormlight Archive · Book 1").fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText("The Way of Kings").fetchSemanticsNode().boundsInRoot
        (series.bottom <= title.top) shouldBe true
    }

    @Test
    fun `a chapter row plays from that chapter`() {
        wide()
        composeRule.onNodeWithTag(chapterRowTag(6)).performClick()
        seeks shouldBe listOf(6)
    }

    @Test
    fun `a chapterless book has no panel, and nothing to pick`() {
        wide(wholeBookState())
        composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Chapters").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        panelShown.last() shouldBe false
    }

    @Test
    fun `a failure puts the card in the transport's place and the panel stays`() {
        wide(chapteredState(phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()), primary = PrimaryControl.Retry))
        composeRule.onNodeWithText("Couldn't play this book.").assertExists()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()
        composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).assertIsDisplayed()
    }
}
