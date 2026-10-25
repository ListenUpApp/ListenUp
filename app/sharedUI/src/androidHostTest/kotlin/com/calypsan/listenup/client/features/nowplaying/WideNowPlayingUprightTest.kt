package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.features.nowplaying.components.CHAPTER_PANEL_TAG
import com.calypsan.listenup.client.testing.assertRightOf
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An upright Expanded tablet (900×1200dp; spec §5.3, C4): three columns don't fit across, but the height carries a
 * cover over the player column, so the panel still shows beside it and Chapters leaves the settings row.
 */
@Config(qualifiers = "w900dp-h1200dp-port")
@RunWith(RobolectricTestRunner::class)
class WideNowPlayingUprightTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val panelShown = mutableListOf<Boolean>()

    @Test
    fun `the player column, cover above, sits beside the panel, with no Chapters button`() {
        composeRule.setContent {
            MaterialTheme { TestWideNowPlaying(state = chapteredState(), onChapterPanelShown = { panelShown += it }) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Chapters").assertDoesNotExist()
        assertRightOf(composeRule.onNodeWithTag(CHAPTER_PANEL_TAG), of = composeRule.onNodeWithText("The Way of Kings"))
        val cover = composeRule.onNodeWithTag(WIDE_COVER_TAG).fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText("The Way of Kings").fetchSemanticsNode().boundsInRoot
        (cover.bottom <= title.top) shouldBe true
        panelShown.last() shouldBe true
    }
}
